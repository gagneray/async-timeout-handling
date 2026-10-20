package fr.exemple.produits;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
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

    /** Code pour une exception non interceptée. HYPOTHÈSE : remplacer par le code technique générique existant. */
    static final EnumCodeErreurTechnique CODE_ERREUR_INATTENDUE = EnumCodeErreurTechnique.ERREUR_TECHNIQUE_INATTENDUE;

    private final AsyncProduitService asyncProduitService;
    private final long timeoutMs;

    public ProduitService(AsyncProduitService asyncProduitService,
                          @Value("${produits.recherche.timeout}") long timeoutMs) {
        this.asyncProduitService = asyncProduitService;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Interroge B et le legacy en parallèle, avec un timeout commun.
     * <ul>
     *   <li>les deux répondent : fusion ;</li>
     *   <li>une seule répond : ses résultats + un message technique pour l'autre (HTTP 200) ;</li>
     *   <li>aucune ne répond (timeout ou exception non interceptée) : {@link TechnicalException} (HTTP 503).</li>
     * </ul>
     * Package-private (au lieu de private) pour les tests unitaires.
     */
    ListeProduits listerProduitsAvecCriteres(CriteresFiltres criteres) {
        long debut = System.nanoTime();

        // Les deux appels partent au même instant : un timeout unique par future équivaut à un timeout global.
        CompletableFuture<ListeProduits> brutB = lancer(() -> asyncProduitService.listerProduitsB(criteres))
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        CompletableFuture<ListeProduits> brutLegacy = lancer(() -> asyncProduitService.listerProduitsA(criteres))
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS);

        // orTimeout AVANT handle : sinon handle ne verrait pas la TimeoutException.
        // Après un timeout, handle s'exécute sur le thread du JDK qui gère les délais : il reste trivial.
        CompletableFuture<ListeProduits> b = brutB
                .handle((liste, ex) -> ex == null ? liste : listeEnErreur(ex, ID_SOURCE_B, debut));
        CompletableFuture<ListeProduits> legacy = brutLegacy
                .handle((liste, ex) -> ex == null ? liste : listeEnErreur(ex, ID_SOURCE_LEGACY, debut));

        // Ne lève pas : les futures issus de handle ne peuvent pas échouer.
        CompletableFuture.allOf(b, legacy).join();

        Throwable echecB = echec(brutB);
        Throwable echecLegacy = echec(brutLegacy);
        if (echecB != null && echecLegacy != null) {
            throw exceptionDeuxSourcesKo(echecB, echecLegacy);
        }
        // Ordre : B puis legacy. Le tri éventuel reste géré en dehors de cette méthode.
        return fusionner(b.join(), legacy.join());
    }

    /** Transforme un échec au lancement (ex. TaskRejectedException) en future en échec, pour un chemin unique. */
    private static CompletableFuture<ListeProduits> lancer(Supplier<CompletableFuture<ListeProduits>> appel) {
        try {
            return appel.get();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** ListeProduits vide portant le message technique de la source en échec. Construction + log uniquement. */
    private ListeProduits listeEnErreur(Throwable ex, String idSource, long debut) {
        Throwable cause = causeReelle(ex);
        long dureeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - debut);
        EnumCodeErreurTechnique code;
        if (cause instanceof TimeoutException) {
            log.warn("Source {} : pas de réponse après {} ms (timeout {} ms), résultat ignoré",
                    idSource, dureeMs, timeoutMs);
            code = EnumCodeErreurTechnique.ASYNC_TIMEOUT;
        } else {
            log.warn("Source {} : échec inattendu après {} ms", idSource, dureeMs, cause);
            code = CODE_ERREUR_INATTENDUE;
        }
        return ListeProduits.builder()
                .produits(new ArrayList<>())
                .total(0)
                .messages(new ArrayList<>(List.of(Message.fromEnum(code, idSource))))
                .build();
    }

    /** Cause de l'échec d'un future déjà terminé, ou null s'il a réussi. Ne bloque pas. */
    private static Throwable echec(CompletableFuture<ListeProduits> future) {
        try {
            future.join();
            return null;
        } catch (CompletionException | CancellationException e) {
            return causeReelle(e);
        }
    }

    private static TechnicalException exceptionDeuxSourcesKo(Throwable echecB, Throwable echecLegacy) {
        EnumCodeErreurTechnique code =
                echecB instanceof TimeoutException && echecLegacy instanceof TimeoutException
                        ? EnumCodeErreurTechnique.ASYNC_TIMEOUT
                        : CODE_ERREUR_INATTENDUE;
        TechnicalException exception = new TechnicalException(code, echecB, ID_SOURCE_B, ID_SOURCE_LEGACY);
        exception.addSuppressed(echecLegacy);
        return exception;
    }

    private static Throwable causeReelle(Throwable ex) {
        Throwable cause = ex;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
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
