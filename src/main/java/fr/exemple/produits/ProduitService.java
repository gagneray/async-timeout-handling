package fr.exemple.produits;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ProduitService {

    private static final Logger log = LoggerFactory.getLogger(ProduitService.class);

    /** Identifiants des sources dans les arguments des messages. HYPOTHÈSE : aligner sur les valeurs existantes. */
    static final String ID_SOURCE_B = "B";
    static final String ID_SOURCE_LEGACY = "A";

    /** Code d'un échec d'appel asynchrone hors timeout. HYPOTHÈSE : remplacer par le code technique générique existant. */
    static final EnumCodeErreurTechnique CODE_ERREUR_APPEL_ASYNC = EnumCodeErreurTechnique.ERREUR_TECHNIQUE_INATTENDUE;

    private final AsyncProduitService asyncProduitService;
    private final long timeoutMs;

    public ProduitService(AsyncProduitService asyncProduitService,
                          @Value("${produits.recherche.timeout}") long timeoutMs) {
        this.asyncProduitService = asyncProduitService;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Interroge B, et le legacy sauf si {@code bSeulement} vaut {@code true}, avec un timeout commun.
     * Une source « sans retour » (total à 0 et au moins un message) n'empêche pas de renvoyer l'autre (HTTP 200).
     * Si toutes les sources interrogées sont sans retour : {@link TechnicalException} (HTTP 503).
     * Package-private (au lieu de private) pour les tests unitaires.
     */
    ListeProduits listerProduitsAvecCriteres(CriteresFiltres criteres) {
        CompletableFuture<ListeProduits> b =
                appelerSource(() -> asyncProduitService.listerProduitsB(criteres), ID_SOURCE_B);

        // Legacy appelé si bSeulement est null ou false ; B seule uniquement si bSeulement vaut true.
        if (Boolean.TRUE.equals(criteres.getBSeulement())) {
            ListeProduits listeB = b.join(); // ne lève pas : voir appelerSource
            if (estSansRetour(listeB)) {
                throw new TechnicalException(CODE_ERREUR_APPEL_ASYNC, ID_SOURCE_B);
            }
            return fusionner(listeB);
        }

        // Lancé juste après B : les deux appels partent quasiment au même instant,
        // un timeout unique par source équivaut donc à un timeout global.
        CompletableFuture<ListeProduits> legacy =
                appelerSource(() -> asyncProduitService.listerProduitsA(criteres), ID_SOURCE_LEGACY);

        CompletableFuture.allOf(b, legacy).join(); // ne lève pas
        ListeProduits listeB = b.join();
        ListeProduits listeLegacy = legacy.join();

        if (estSansRetour(listeB) && estSansRetour(listeLegacy)) {
            // Le détail a déjà été logué : par listeEnErreur (timeout, exception) ou par les services sources.
            throw new TechnicalException(CODE_ERREUR_APPEL_ASYNC, ID_SOURCE_B, ID_SOURCE_LEGACY);
        }
        // Ordre : B puis legacy. Le tri éventuel reste géré en dehors de cette méthode.
        return fusionner(listeB, listeLegacy);
    }

    /**
     * Lance l'appel d'une source et renvoie un future qui n'échoue jamais :
     * succès -> ListeProduits de la source ; timeout, exception ou rejet au lancement -> ListeProduits d'erreur.
     * L'ordre orTimeout puis handle est garanti ici : dans l'ordre inverse, la TimeoutException ne serait pas interceptée.
     */
    private CompletableFuture<ListeProduits> appelerSource(Supplier<CompletableFuture<ListeProduits>> appel,
                                                           String idSource) {
        CompletableFuture<ListeProduits> future;
        try {
            future = appel.get();
        } catch (RuntimeException e) {
            // Ex. TaskRejectedException levée par le proxy @Async : même chemin qu'un future en échec.
            future = CompletableFuture.failedFuture(e);
        }
        return future
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .handle((liste, ex) -> ex == null ? liste : listeEnErreur(ex, idSource));
    }

    /**
     * ListeProduits vide portant le message technique de la source en échec.
     * Après un timeout, s'exécute sur le thread unique du JDK qui gère les délais : construction + log uniquement.
     */
    private ListeProduits listeEnErreur(Throwable ex, String idSource) {
        EnumCodeErreurTechnique code;
        if (ex instanceof TimeoutException) {
            log.warn("Source {} : pas de réponse dans le délai de {} ms, résultat ignoré", idSource, timeoutMs);
            code = EnumCodeErreurTechnique.ASYNC_TIMEOUT;
        } else {
            log.warn("Source {} : échec de l'appel asynchrone", idSource, ex); // stack trace complète, causes comprises
            code = CODE_ERREUR_APPEL_ASYNC;
        }
        return ListeProduits.builder()
                .produits(new ArrayList<>())
                .total(0)
                .messages(new ArrayList<>(List.of(Message.fromEnum(code, idSource))))
                .build();
    }

    /** Aucun produit et au moins un message : erreur de la source, timeout ou exception. */
    private static boolean estSansRetour(ListeProduits liste) {
        return liste.getTotal() == 0 && liste.getMessages() != null && !liste.getMessages().isEmpty();
    }

    /** Nouvel objet : on ne modifie ni ne réutilise les ListeProduits des sources. */
    private static ListeProduits fusionner(ListeProduits... listes) {
        ListeProduits resultat = ListeProduits.builder()
                .produits(new ArrayList<>())
                .total(0)
                .messages(new ArrayList<>())
                .build();
        for (ListeProduits liste : listes) {
            if (liste.getProduits() != null) {
                resultat.getProduits().addAll(liste.getProduits());
            }
            if (liste.getMessages() != null) {
                resultat.getMessages().addAll(liste.getMessages());
            }
            resultat.setTotal(resultat.getTotal() + liste.getTotal());
        }
        return resultat;
    }
}
