package fr.exemple.produits;

import static fr.exemple.produits.ProduitService.ID_SOURCE_B;
import static fr.exemple.produits.ProduitService.ID_SOURCE_LEGACY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * Chemin réel : proxy @Async -> exécuteur à threads virtuels -> services sources (mocks Mockito avec un vrai sleep).
 * Les vrais AsyncProduitService et ProduitService sont enregistrés tels quels (constructeurs autowirés).
 */
@SpringJUnitConfig(classes = {
        ProduitServiceAsyncIntegrationTest.Config.class,
        AsyncProduitService.class,
        ProduitService.class
})
@TestPropertySource(properties = "produits.recherche.timeout=" + ProduitServiceAsyncIntegrationTest.TIMEOUT_MS)
@Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ProduitServiceAsyncIntegrationTest {

    static final long TIMEOUT_MS = 300;
    private static final long LENT_MS = 1_500;
    private static final long MARGE_MS = 400;

    @Configuration
    @EnableAsync
    static class Config {

        /** Équivalent minimal du bean existant (sans TaskDecorator, hors périmètre). */
        @Bean(name = "taskExecutor")
        TaskExecutor taskExecutor() {
            SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("it-produits-");
            executor.setVirtualThreads(true);
            return executor;
        }

        @Bean
        ProduitBService produitBService() {
            return mock(ProduitBService.class);
        }

        @Bean
        ProduitAService produitAService() {
            return mock(ProduitAService.class);
        }
    }

    @Autowired private ProduitService produitService;
    @Autowired private AsyncProduitService asyncProduitService;
    @Autowired private ProduitBService produitBService;
    @Autowired private ProduitAService produitAService;

    /** Threads sur lesquels les sources ont réellement été appelées. */
    private final Set<Thread> threadsAppels = ConcurrentHashMap.newKeySet();

    @AfterEach
    void resetMocks() {
        reset(produitBService, produitAService);
    }

    @Test
    void legacy_lent_reponse_partielle_de_B_dans_le_delai_via_le_proxy_async() {
        when(produitBService.listerProduits(any())).thenAnswer(inv -> repondre(0, liste(new Produit("b1"))));
        when(produitAService.listerProduits(any())).thenAnswer(inv -> repondre(LENT_MS, liste(new Produit("a1"))));

        long debut = System.nanoTime();
        ListeProduits resultat = produitService.listerProduitsAvecCriteres(new CriteresFiltres());
        long dureeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - debut);

        assertThat(AopUtils.isAopProxy(asyncProduitService)).isTrue();
        assertThat(resultat.getProduits()).containsExactly(new Produit("b1"));
        assertThat(resultat.getTotal()).isEqualTo(1);
        assertThat(resultat.getMessages())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(Message.fromEnum(EnumCodeErreurTechnique.ASYNC_TIMEOUT, ID_SOURCE_LEGACY));
        assertThat(dureeMs).isBetween(TIMEOUT_MS - 20, TIMEOUT_MS + MARGE_MS);
        assertAppelsSurThreadsVirtuels();
    }

    @Test
    void deux_sources_rapides_fusion_via_le_proxy_async() {
        when(produitBService.listerProduits(any())).thenAnswer(inv -> repondre(50, liste(new Produit("b1"))));
        when(produitAService.listerProduits(any())).thenAnswer(inv -> repondre(100, liste(new Produit("a1"))));

        ListeProduits resultat = produitService.listerProduitsAvecCriteres(new CriteresFiltres());

        assertThat(resultat.getProduits()).containsExactly(new Produit("b1"), new Produit("a1"));
        assertThat(resultat.getTotal()).isEqualTo(2);
        assertThat(resultat.getMessages()).isEmpty();
        assertAppelsSurThreadsVirtuels();
    }

    @Test
    void deux_sources_lentes_technical_exception_dans_le_delai() {
        when(produitBService.listerProduits(any())).thenAnswer(inv -> repondre(LENT_MS, liste(new Produit("b1"))));
        when(produitAService.listerProduits(any())).thenAnswer(inv -> repondre(LENT_MS, liste(new Produit("a1"))));

        long debut = System.nanoTime();
        assertThatThrownBy(() -> produitService.listerProduitsAvecCriteres(new CriteresFiltres()))
                .isInstanceOf(TechnicalException.class);
        long dureeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - debut);

        assertThat(dureeMs).isBetween(TIMEOUT_MS - 20, TIMEOUT_MS + MARGE_MS);
    }

    @Test
    void exception_non_interceptee_dans_une_source_reponse_partielle() {
        when(produitBService.listerProduits(any())).thenThrow(new IllegalStateException("bug B"));
        when(produitAService.listerProduits(any())).thenAnswer(inv -> repondre(50, liste(new Produit("a1"))));

        ListeProduits resultat = produitService.listerProduitsAvecCriteres(new CriteresFiltres());

        assertThat(resultat.getProduits()).containsExactly(new Produit("a1"));
        assertThat(resultat.getMessages())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(Message.fromEnum(ProduitService.CODE_ERREUR_APPEL_ASYNC, ID_SOURCE_B));
    }

    private ListeProduits repondre(long delaiMs, ListeProduits liste) throws InterruptedException {
        threadsAppels.add(Thread.currentThread());
        Thread.sleep(delaiMs);
        return liste;
    }

    private void assertAppelsSurThreadsVirtuels() {
        assertThat(threadsAppels)
                .hasSize(2)
                .allMatch(Thread::isVirtual, "thread virtuel")
                .doesNotContain(Thread.currentThread());
    }

    private static ListeProduits liste(Produit... produits) {
        return ListeProduits.builder()
                .produits(new ArrayList<>(List.of(produits)))
                .total(produits.length)
                .messages(new ArrayList<>())
                .build();
    }
}
