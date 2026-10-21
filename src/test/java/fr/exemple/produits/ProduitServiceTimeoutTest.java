package fr.exemple.produits;

import static fr.exemple.produits.ProduitService.CODE_ERREUR_APPEL_ASYNC;
import static fr.exemple.produits.ProduitService.ID_SOURCE_B;
import static fr.exemple.produits.ProduitService.ID_SOURCE_LEGACY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.task.TaskRejectedException;

/**
 * Tests unitaires sans contexte Spring : AsyncProduitService est mocké et renvoie des futures pilotés à la main.
 * Le @Timeout en SEPARATE_THREAD protège contre un blocage (join() n'est pas interruptible).
 */
@Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ProduitServiceTimeoutTest {

    private static final long TIMEOUT_MS = 200;
    /** Marge pour le scheduling (CI chargée) : le test vérifie un ordre de grandeur, pas une précision. */
    private static final long MARGE_MS = 300;

    /** bSeulement = false par défaut : les deux sources sont interrogées. */
    private final CriteresFiltres criteres = new CriteresFiltres();
    private AsyncProduitService async;
    private ProduitService service;

    @BeforeEach
    void setUp() {
        async = mock(AsyncProduitService.class);
        service = new ProduitService(async, TIMEOUT_MS);
    }

    // ---------- Les deux sources répondent ----------

    @Test
    void les_deux_sources_repondent_fusion_des_produits_totaux_et_messages_B_puis_legacy() {
        Message messageB = Message.fromEnum(EnumCodeErreurFonctionnel.AVERTISSEMENT_FONCTIONNEL, ID_SOURCE_B);
        Message messageLegacy = Message.fromEnum(EnumCodeErreurFonctionnel.AVERTISSEMENT_FONCTIONNEL, ID_SOURCE_LEGACY);
        siB(immediat(liste(List.of(produit("b1"), produit("b2")), 2, messageB)));
        siLegacy(immediat(liste(List.of(produit("a1")), 1, messageLegacy)));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).containsExactly(produit("b1"), produit("b2"), produit("a1"));
        assertThat(resultat.getTotal()).isEqualTo(3);
        assertThat(resultat.getMessages()).containsExactly(messageB, messageLegacy);
    }

    @Test
    void les_deux_sources_repondent_sans_resultat_ni_message_200_avec_liste_vide() {
        siB(immediat(liste(List.of(), 0)));
        siLegacy(immediat(liste(List.of(), 0)));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).isEmpty();
        assertThat(resultat.getTotal()).isZero();
        assertThat(resultat.getMessages()).isEmpty();
    }

    // ---------- Erreur renvoyée par une source elle-même (total 0 + message) ----------

    @Test
    void B_renvoie_sa_propre_erreur_legacy_repond_reponse_partielle() {
        Message erreurB = Message.fromEnum(EnumCodeErreurTechnique.ERREUR_APPEL_SOURCE, ID_SOURCE_B);
        siB(immediat(liste(List.of(), 0, erreurB)));
        siLegacy(immediat(liste(List.of(produit("a1")), 1)));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).containsExactly(produit("a1"));
        assertThat(resultat.getTotal()).isEqualTo(1);
        assertThat(resultat.getMessages()).containsExactly(erreurB);
    }

    @Test
    void les_deux_sources_renvoient_leur_propre_erreur_technical_exception() {
        siB(immediat(liste(List.of(), 0, Message.fromEnum(EnumCodeErreurTechnique.ERREUR_APPEL_SOURCE, ID_SOURCE_B))));
        siLegacy(immediat(liste(List.of(), 0,
                Message.fromEnum(EnumCodeErreurTechnique.ERREUR_APPEL_SOURCE, ID_SOURCE_LEGACY))));

        assertThatThrownBy(() -> service.listerProduitsAvecCriteres(criteres))
                .isInstanceOf(TechnicalException.class);
    }

    @Test
    void B_renvoie_sa_propre_erreur_legacy_en_timeout_technical_exception() {
        siB(immediat(liste(List.of(), 0, Message.fromEnum(EnumCodeErreurTechnique.ERREUR_APPEL_SOURCE, ID_SOURCE_B))));
        siLegacy(jamais());

        assertThatThrownBy(() -> service.listerProduitsAvecCriteres(criteres))
                .isInstanceOf(TechnicalException.class);
    }

    @Test
    void B_renvoie_des_produits_et_un_message_ce_n_est_pas_sans_retour() {
        Message messageB = Message.fromEnum(EnumCodeErreurTechnique.ERREUR_APPEL_SOURCE, ID_SOURCE_B);
        siB(immediat(liste(List.of(produit("b1")), 1, messageB)));
        siLegacy(jamais());

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).containsExactly(produit("b1"));
        assertThat(resultat.getMessages())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(messageB, timeout(ID_SOURCE_LEGACY));
    }

    // ---------- Une source en timeout ----------

    @Test
    void B_repond_legacy_ne_repond_jamais_resultats_de_B_et_message_timeout_legacy_dans_le_delai() {
        siB(immediat(liste(List.of(produit("b1")), 1)));
        siLegacy(jamais());

        long debut = System.nanoTime();
        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);
        long dureeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - debut);

        assertThat(resultat.getProduits()).containsExactly(produit("b1"));
        assertThat(resultat.getTotal()).isEqualTo(1);
        assertThat(resultat.getMessages())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(timeout(ID_SOURCE_LEGACY));
        assertThat(dureeMs).isBetween(TIMEOUT_MS - 20, TIMEOUT_MS + MARGE_MS);
    }

    @Test
    void B_repond_legacy_repond_trop_tard_son_resultat_tardif_est_ignore() throws Exception {
        siB(immediat(liste(List.of(produit("b1")), 1)));
        siLegacy(tardif(liste(List.of(produit("a-tardif")), 1), 3 * TIMEOUT_MS));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);
        Thread.sleep(3 * TIMEOUT_MS); // la réponse tardive arrive pendant ce temps

        assertThat(resultat.getProduits()).containsExactly(produit("b1"));
        assertThat(resultat.getTotal()).isEqualTo(1);
        assertThat(resultat.getMessages())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(timeout(ID_SOURCE_LEGACY));
    }

    @Test
    void B_en_timeout_legacy_repond_cas_symetrique() {
        siB(jamais());
        siLegacy(tardif(liste(List.of(produit("a1")), 1), TIMEOUT_MS / 2));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).containsExactly(produit("a1"));
        assertThat(resultat.getTotal()).isEqualTo(1);
        assertThat(resultat.getMessages())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(timeout(ID_SOURCE_B));
    }

    // ---------- Une source en exception non interceptée ou rejetée au lancement ----------

    @Test
    void B_repond_legacy_en_echec_resultats_de_B_et_message_technique_legacy() {
        siB(immediat(liste(List.of(produit("b1")), 1)));
        siLegacy(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).containsExactly(produit("b1"));
        assertThat(resultat.getTotal()).isEqualTo(1);
        assertThat(resultat.getMessages())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(erreurAsync(ID_SOURCE_LEGACY));
    }

    @Test
    void B_en_echec_n_empeche_pas_de_recuperer_le_legacy_probleme_des_join_successifs() {
        siB(CompletableFuture.failedFuture(new CompletionException(new IllegalStateException("boom"))));
        siLegacy(tardif(liste(List.of(produit("a1")), 1), TIMEOUT_MS / 2));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).containsExactly(produit("a1"));
        assertThat(resultat.getMessages())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(erreurAsync(ID_SOURCE_B));
    }

    @Test
    void echec_au_lancement_du_legacy_meme_chemin_qu_un_future_en_echec() {
        siB(immediat(liste(List.of(produit("b1")), 1)));
        when(async.listerProduitsA(any())).thenThrow(new TaskRejectedException("exécuteur saturé"));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).containsExactly(produit("b1"));
        assertThat(resultat.getMessages())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(erreurAsync(ID_SOURCE_LEGACY));
    }

    @Test
    void echec_au_lancement_de_B_le_legacy_est_quand_meme_appele() {
        when(async.listerProduitsB(any())).thenThrow(new TaskRejectedException("exécuteur saturé"));
        siLegacy(immediat(liste(List.of(produit("a1")), 1)));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).containsExactly(produit("a1"));
        assertThat(resultat.getMessages())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(erreurAsync(ID_SOURCE_B));
    }

    // ---------- Les deux sources sans retour : 503 ----------

    @Test
    void les_deux_sources_en_timeout_technical_exception_dans_le_delai() {
        siB(jamais());
        siLegacy(jamais());

        long debut = System.nanoTime();
        assertThatThrownBy(() -> service.listerProduitsAvecCriteres(criteres))
                .isInstanceOf(TechnicalException.class);
        long dureeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - debut);

        assertThat(dureeMs).isBetween(TIMEOUT_MS - 20, TIMEOUT_MS + MARGE_MS);
    }

    @Test
    void les_deux_sources_en_echec_technical_exception() {
        siB(CompletableFuture.failedFuture(new CompletionException(new IllegalStateException("boom B"))));
        siLegacy(CompletableFuture.failedFuture(new IllegalArgumentException("boom legacy")));

        assertThatThrownBy(() -> service.listerProduitsAvecCriteres(criteres))
                .isInstanceOf(TechnicalException.class);
    }

    @Test
    void B_en_timeout_et_legacy_rejete_au_lancement_technical_exception() {
        siB(jamais());
        when(async.listerProduitsA(any())).thenThrow(new TaskRejectedException("exécuteur saturé"));

        assertThatThrownBy(() -> service.listerProduitsAvecCriteres(criteres))
                .isInstanceOf(TechnicalException.class);
    }

    // ---------- Mode bSeulement : le legacy n'est jamais appelé, B sans retour => 503 ----------

    @Test
    void bSeulement_B_repond_resultats_de_B_et_legacy_jamais_appele() {
        criteres.setBSeulement(true);
        Message messageB = Message.fromEnum(EnumCodeErreurFonctionnel.AVERTISSEMENT_FONCTIONNEL, ID_SOURCE_B);
        siB(immediat(liste(List.of(produit("b1")), 1, messageB)));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).containsExactly(produit("b1"));
        assertThat(resultat.getTotal()).isEqualTo(1);
        assertThat(resultat.getMessages()).containsExactly(messageB);
        verify(async, never()).listerProduitsA(any());
    }

    @Test
    void bSeulement_B_sans_resultat_ni_message_200_avec_liste_vide() {
        criteres.setBSeulement(true);
        siB(immediat(liste(List.of(), 0)));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).isEmpty();
        assertThat(resultat.getMessages()).isEmpty();
        verify(async, never()).listerProduitsA(any());
    }

    @Test
    void bSeulement_B_en_timeout_technical_exception_dans_le_delai() {
        criteres.setBSeulement(true);
        siB(jamais());

        long debut = System.nanoTime();
        assertThatThrownBy(() -> service.listerProduitsAvecCriteres(criteres))
                .isInstanceOf(TechnicalException.class);
        long dureeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - debut);

        assertThat(dureeMs).isBetween(TIMEOUT_MS - 20, TIMEOUT_MS + MARGE_MS);
        verify(async, never()).listerProduitsA(any());
    }

    @Test
    void bSeulement_B_en_echec_technical_exception() {
        criteres.setBSeulement(true);
        siB(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        assertThatThrownBy(() -> service.listerProduitsAvecCriteres(criteres))
                .isInstanceOf(TechnicalException.class);
        verify(async, never()).listerProduitsA(any());
    }

    @Test
    void bSeulement_B_rejete_au_lancement_technical_exception() {
        criteres.setBSeulement(true);
        when(async.listerProduitsB(any())).thenThrow(new TaskRejectedException("exécuteur saturé"));

        assertThatThrownBy(() -> service.listerProduitsAvecCriteres(criteres))
                .isInstanceOf(TechnicalException.class);
        verify(async, never()).listerProduitsA(any());
    }

    @Test
    void bSeulement_B_renvoie_sa_propre_erreur_technical_exception() {
        criteres.setBSeulement(true);
        siB(immediat(liste(List.of(), 0, Message.fromEnum(EnumCodeErreurTechnique.ERREUR_APPEL_SOURCE, ID_SOURCE_B))));

        assertThatThrownBy(() -> service.listerProduitsAvecCriteres(criteres))
                .isInstanceOf(TechnicalException.class);
        verify(async, never()).listerProduitsA(any());
    }

    // ---------- Outils ----------

    private void siB(CompletableFuture<ListeProduits> future) {
        when(async.listerProduitsB(any())).thenReturn(future);
    }

    private void siLegacy(CompletableFuture<ListeProduits> future) {
        when(async.listerProduitsA(any())).thenReturn(future);
    }

    private static CompletableFuture<ListeProduits> immediat(ListeProduits liste) {
        return CompletableFuture.completedFuture(liste);
    }

    private static CompletableFuture<ListeProduits> tardif(ListeProduits liste, long delaiMs) {
        return CompletableFuture.supplyAsync(() -> liste,
                CompletableFuture.delayedExecutor(delaiMs, TimeUnit.MILLISECONDS));
    }

    private static CompletableFuture<ListeProduits> jamais() {
        return new CompletableFuture<>();
    }

    private static Message timeout(String idSource) {
        return Message.fromEnum(EnumCodeErreurTechnique.ASYNC_TIMEOUT, idSource);
    }

    private static Message erreurAsync(String idSource) {
        return Message.fromEnum(CODE_ERREUR_APPEL_ASYNC, idSource);
    }

    private static ListeProduits liste(List<Produit> produits, int total, Message... messages) {
        return ListeProduits.builder()
                .produits(new ArrayList<>(produits))
                .total(total)
                .messages(new ArrayList<>(List.of(messages)))
                .build();
    }

    /** À adapter au DTO produit réel. */
    private static Produit produit(String id) {
        return new Produit(id);
    }
}
