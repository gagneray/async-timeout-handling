# Timeout sur la recherche multicritère `/ListerProducts`

## 1. Résumé de la demande

Dans `ProduitService.listerProduitsAvecCriteres`, A interroge en parallèle deux sources via `@Async` : B (REST) et le legacy (SOAP, nommé « A » dans le code). Aujourd'hui, deux défauts :

- **pas de timeout** : un legacy lent bloque toute la réponse ;
- **des `join()` successifs dans un seul `try/catch`** : l'échec d'un future fait perdre le résultat de l'autre.

L'objectif :

- un **timeout unique**, externalisé en propriété ;
- une **réponse partielle (200)** quand une seule source répond, avec un message technique pour l'autre ;
- un **503** (`TechnicalException`) quand aucune source ne répond.

Le tout sans nouveau DTO et sans toucher à `AsyncProduitService`, aux services sources ni au handler d'erreurs.

## 2. Hypothèses retenues et points à confirmer

Les réponses à mes questions sont intégrées. Pour les points restés ouverts, j'ai pris les hypothèses suivantes. Chacune se change en un seul endroit.

| # | Sujet | Hypothèse | Où l'ajuster |
|---|---|---|---|
| H1 | Champs de `ListeProduits` | `produits`, `total`, `messages` ; type d'élément `Produit` (le code de production ne nomme jamais ce type, seuls les tests le font) | accesseurs Lombok dans `fusionner` et `listeEnErreur` |
| H2 | Identifiants de source | `ID_SOURCE_B = "B"`, `ID_SOURCE_LEGACY = "A"` | constantes de `ProduitService` |
| H3 | Code d'une exception non interceptée | `ERREUR_TECHNIQUE_INATTENDUE` (placeholder) | constante `CODE_ERREUR_INATTENDUE` |
| H4 | Valeurs de `ASYNC_TIMEOUT` | `"<CODE>"` et `"<libellé> : %s"`, à fixer par toi | enum |
| H5 | Injection | Par constructeur, `@Value("${produits.recherche.timeout}") long` en millisecondes | constructeur (voir § 5.3, note) |
| H6 | Visibilité | `listerProduitsAvecCriteres` passe de `private` à package-private, pour l'appeler depuis les tests | — |
| H7 | Outillage | SLF4J ; AssertJ, Mockito et JUnit ≥ 5.9 côté tests | — |
| H8 | Test d'intégration | Exécuteur à threads virtuels minimal redéclaré dans le test, sans `TaskDecorator` | classe `Config` du test |
| H9 | Ordre d'agrégation | B puis le legacy (concaténation) ; le tri éventuel reste là où il est | — |

> **Changement de comportement assumé.** Avant, une `RuntimeException` ou une `Error` non interceptée dans **une seule** source était relancée et faisait échouer toute la requête. Désormais, cette source est traitée comme KO : réponse partielle 200 avec un message technique, et un 503 seulement si l'autre source est KO elle aussi. Les `Error` (par exemple `StackOverflowError`) sont incluses : elles sont loguées avec leur stack trace, mais ne cassent plus la réponse de l'autre source.

## 3. Choix retenus

**`orTimeout` + `handle` + `allOf`.** Chaque future reçoit son propre `orTimeout`, puis un `handle` qui transforme tous les cas (succès, timeout, exception) en `ListeProduits`. Le future qui en résulte ne peut plus échouer, donc `allOf(...).join()` ne lève jamais et attend les deux sources. Le défaut des `join()` successifs disparaît : un échec n'interrompt plus la lecture de l'autre résultat.

**Timeout unique.** Les deux appels partent au même instant, le même délai appliqué à chacun équivaut donc à un timeout global. On sait en plus quelle source a dépassé, ce qu'un `orTimeout` posé sur `allOf` ne dirait pas. La valeur est un `long` en millisecondes, comme dans le YAML : pas d'ambiguïté de conversion `Duration`.

**Règle du 503.** Une source est KO uniquement si **son future** a échoué : timeout, exception non interceptée ou rejet au lancement. Le service le lit sur le future brut, une fois les deux terminés. Ce `join()` ne bloque pas, puisque le future est déjà terminé à ce stade. Une source qui *répond* avec un message technique dans son `ListeProduits` a répondu : elle ne compte pas comme KO. C'est pourquoi la décision ne repose pas sur `Message.code`. Une liste vide sans erreur reste un 200, puisque c'est une recherche infructueuse.

**Code de la `TechnicalException`.** Si les deux sources sont en timeout, le code est `ASYNC_TIMEOUT`. Dans tous les autres cas, c'est le code générique. La cause est celle de B, déballée ; celle du legacy est ajoutée en `suppressed`, donc visible dans la stack trace loguée par le handler. Les arguments sont `"B", "A"`. Avec un template à un seul `%s`, `String.format` ignore le second argument : adapte le template si tu veux les deux.

**Réutilisation de `ListeProduits` et `Message`.**
- Une source en échec est représentée par un `ListeProduits` vide portant `Message.fromEnum(code, idSource)`.
- La fusion construit **un nouvel objet** et copie les listes : on ne modifie jamais l'objet d'une source.
- Aucun record, DTO ou enum parallèle n'est introduit : uniquement des helpers privés dans `ProduitService`.

## 4. Comportement cas par cas

| B | Legacy | HTTP | Produits / total | Messages |
|---|---|---|---|---|
| répond | répond | 200 | B puis legacy / somme | messages de B puis messages du legacy |
| répond, liste vide | répond, liste vide | 200 | vide / 0 | messages éventuels des sources |
| répond | timeout | 200 | B / total de B | messages de B + `ASYNC_TIMEOUT("A")` |
| timeout | répond | 200 | legacy / total du legacy | `ASYNC_TIMEOUT("B")` + messages du legacy |
| répond | exception non interceptée **ou** rejet au lancement | 200 | B / total de B | messages de B + `CODE_ERREUR_INATTENDUE("A")` |
| exception non interceptée **ou** rejet au lancement | répond | 200 | legacy / total du legacy | `CODE_ERREUR_INATTENDUE("B")` + messages du legacy |
| répond **avec un message technique** | timeout ou exception | 200 | B | message technique de B + message de la source KO |
| timeout | timeout | **503** | — | `TechnicalException(ASYNC_TIMEOUT, cause TimeoutException, "B", "A")` |
| KO (au moins une exception) | KO | **503** | — | `TechnicalException(CODE_ERREUR_INATTENDUE, cause de B, "B", "A")`, cause du legacy en `suppressed` |

Le temps de réponse est borné par `produits.recherche.timeout`, à quelques millisecondes près.

## 5. Code

### 5.1 `EnumCodeErreurTechnique` : constante ajoutée

```java
/** Une source n'a pas répondu dans le délai produits.recherche.timeout. Argument : identifiant de la source. */
ASYNC_TIMEOUT("<CODE>", "<libellé> : %s"),
```

### 5.2 `application.yaml`

```yaml
produits:
  recherche:
    # Timeout commun aux deux sources, en millisecondes, calé sur le legacy (la source la plus lente).
    timeout: 15000
```

### 5.3 `ProduitService`

Seuls les éléments ajoutés ou modifiés sont montrés. Les autres méthodes et dépendances de la classe restent en l'état. Le package `fr.exemple.produits` est fictif : adapte le package et les imports des classes métier, dans le code comme dans les tests.

> **Note sur le constructeur (H5).** Si la classe utilise `@RequiredArgsConstructor` de Lombok, `@Value` ne sera pas recopié sur le paramètre. Deux options : écrire le constructeur à la main comme ci-dessous, ou garder un champ non final `@Value("${produits.recherche.timeout}") private long timeoutMs;` et le renseigner dans les tests via `ReflectionTestUtils.setField(service, "timeoutMs", 200L)`.

```java
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
```

## 6. Points de vigilance

1. **L'ordre compte : `orTimeout` avant `handle`.** `orTimeout` fait échouer le future sur lequel il est appelé. Posé avant `handle`, il fait échouer le future brut de la source, et `handle` reçoit alors la `TimeoutException` et la convertit. Dans l'ordre inverse, c'est le future issu de `handle` qui échoue *après* le `handle` : la `TimeoutException` n'est plus interceptée et remonte par `allOf(...).join()`, ce qui recrée le problème initial.

2. **Le thread qui exécute `handle` varie.** Il y a trois cas :
   - le future est déjà terminé quand `handle` est attaché : thread de la requête ;
   - la source termine plus tard : thread virtuel de la source ;
   - timeout : thread `CompletableFutureDelayScheduler` (vérifié à l'exécution, voir § 9).

   Ce dernier est un thread démon **unique pour toute la JVM**, qui sert à tous les `orTimeout` et `delayedExecutor`. Il n'a pas le contexte du `TaskDecorator` : ni `RequestAttributes`, ni `SecurityContext`, ni MDC. C'est pourquoi `listeEnErreur` se limite à construire un message et à loguer : aucun appel bloquant, aucun accès au contexte de la requête.

3. **Le timeout arrête l'attente, pas l'appel.** `orTimeout` ne fait que terminer le future. Même `cancel(true)` n'interrompt pas la tâche d'un `CompletableFuture`. L'appel SOAP continue donc sur son thread virtuel jusqu'à sa fin réseau. Son résultat tardif est ignoré : le `complete(...)` de Spring sur un future déjà terminé est sans effet. Couper l'appel lui-même relève des timeouts réseau des clients (§ 10).

4. **Le lancement peut échouer.** Avec un retour `CompletableFuture`, le proxy `@Async` soumet la tâche à l'exécuteur de façon synchrone. Un refus (`TaskRejectedException`) est donc levé **par l'appel lui-même**. Sans protection, un refus sur B empêcherait même de lancer le legacy. `lancer(...)` le convertit en `failedFuture`, qui suit le même chemin qu'un échec ordinaire.

5. **Déballage et logs.** `causeReelle` retire les enveloppes `CompletionException` et `ExecutionException` : le proxy Spring, par exemple, enveloppe en `CompletionException` une exception levée par le service. Le log est en WARN, avec la source et la durée depuis le lancement :
   - pour un timeout, **sans** stack trace (elle n'apporterait rien) ;
   - pour une exception inattendue, **avec** stack trace (passée en dernier argument SLF4J).

6. **Pas de réutilisation après un timeout.** Après un timeout, `handle` ne voit jamais l'objet de la source : il construit une liste vide neuve. Et même en cas de succès, `fusionner` crée un nouvel objet et copie les listes. On ne détient donc jamais de référence partagée avec un thread source qui serait encore actif.

## 7. Tests unitaires (JUnit 5, sans contexte Spring)

`AsyncProduitService` est mocké : chaque test lui fait renvoyer un future piloté à la main. Le timeout est de 200 ms.

| Comportement simulé | Future utilisé |
|---|---|
| réponse immédiate | `completedFuture` |
| réponse tardive | `supplyAsync` + `delayedExecutor` |
| jamais de réponse | `new CompletableFuture<>()` |
| future en échec | `failedFuture` |
| échec au lancement | `thenThrow(new TaskRejectedException(...))` |

Le `@Timeout` de classe tourne en `SEPARATE_THREAD`, car `join()` n'est pas interruptible : un test bloqué échoue donc au lieu de bloquer la build. Les bornes de durée gardent une marge de 300 ms pour les CI chargées.

> La fabrique `produit(id)` et les `containsExactly(produit(...))` supposent que `Produit` a un constructeur à un argument et un `equals`, ce que fournit Lombok `@Data`. À adapter au DTO réel. Les messages sont comparés champ par champ (`usingRecursiveFieldByFieldElementComparator`), ce qui ne dépend pas de l'existence d'un `equals` sur `Message`.

```java
package fr.exemple.produits;

import static fr.exemple.produits.ProduitService.CODE_ERREUR_INATTENDUE;
import static fr.exemple.produits.ProduitService.ID_SOURCE_B;
import static fr.exemple.produits.ProduitService.ID_SOURCE_LEGACY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    private final CriteresFiltres criteres = new CriteresFiltres();
    private AsyncProduitService async;
    private ProduitService service;

    @BeforeEach
    void setUp() {
        async = mock(AsyncProduitService.class);
        service = new ProduitService(async, TIMEOUT_MS);
    }

    // ---------- B répond / legacy répond ----------

    @Test
    void les_deux_sources_repondent_fusion_des_produits_totaux_et_messages_B_puis_legacy() {
        Message messageB = Message.fromEnum(EnumCodeErreurFonctionnel.AUCUN_RESULTAT, ID_SOURCE_B);
        Message messageLegacy = Message.fromEnum(EnumCodeErreurTechnique.ERREUR_APPEL_SOURCE, ID_SOURCE_LEGACY);
        siB(immediat(liste(List.of(produit("b1"), produit("b2")), 2, messageB)));
        siLegacy(immediat(liste(List.of(produit("a1")), 1, messageLegacy)));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).containsExactly(produit("b1"), produit("b2"), produit("a1"));
        assertThat(resultat.getTotal()).isEqualTo(3);
        assertThat(resultat.getMessages()).containsExactly(messageB, messageLegacy);
    }

    @Test
    void les_deux_sources_repondent_sans_resultat_200_avec_liste_vide_et_pas_de_503() {
        siB(immediat(liste(List.of(), 0)));
        siLegacy(immediat(liste(List.of(), 0)));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).isEmpty();
        assertThat(resultat.getTotal()).isZero();
        assertThat(resultat.getMessages()).isEmpty();
    }

    @Test
    void une_source_qui_repond_avec_une_erreur_technique_compte_comme_ayant_repondu() {
        // B répond avec son propre message technique, le legacy ne répond jamais : 200, pas 503.
        Message messageB = Message.fromEnum(EnumCodeErreurTechnique.ERREUR_APPEL_SOURCE, ID_SOURCE_B);
        siB(immediat(liste(List.of(), 0, messageB)));
        siLegacy(jamais());

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getMessages())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(messageB, timeout(ID_SOURCE_LEGACY));
    }

    // ---------- Une source en timeout ----------

    @Test
    void B_repond_legacy_ne_repond_jamais_resultats_de_B_et_message_timeout_legacy_dans_le_delai() {
        Message messageB = Message.fromEnum(EnumCodeErreurFonctionnel.AUCUN_RESULTAT, ID_SOURCE_B);
        siB(immediat(liste(List.of(produit("b1")), 1, messageB)));
        siLegacy(jamais());

        long debut = System.nanoTime();
        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);
        long dureeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - debut);

        assertThat(resultat.getProduits()).containsExactly(produit("b1"));
        assertThat(resultat.getTotal()).isEqualTo(1);
        assertThat(resultat.getMessages())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(messageB, timeout(ID_SOURCE_LEGACY));
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

    // ---------- Une source en exception non interceptée ----------

    @Test
    void B_repond_legacy_en_echec_resultats_de_B_et_message_technique_legacy() {
        siB(immediat(liste(List.of(produit("b1")), 1)));
        siLegacy(CompletableFuture.failedFuture(new IllegalStateException("boom")));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).containsExactly(produit("b1"));
        assertThat(resultat.getTotal()).isEqualTo(1);
        assertThat(resultat.getMessages())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(Message.fromEnum(CODE_ERREUR_INATTENDUE, ID_SOURCE_LEGACY));
    }

    @Test
    void B_en_echec_n_empeche_pas_de_recuperer_le_legacy_probleme_des_join_successifs() {
        siB(CompletableFuture.failedFuture(new CompletionException(new IllegalStateException("boom"))));
        siLegacy(tardif(liste(List.of(produit("a1")), 1), TIMEOUT_MS / 2));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).containsExactly(produit("a1"));
        assertThat(resultat.getMessages())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(Message.fromEnum(CODE_ERREUR_INATTENDUE, ID_SOURCE_B));
    }

    @Test
    void echec_au_lancement_du_legacy_meme_chemin_qu_un_future_en_echec() {
        siB(immediat(liste(List.of(produit("b1")), 1)));
        when(async.listerProduitsA(any())).thenThrow(new TaskRejectedException("exécuteur saturé"));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).containsExactly(produit("b1"));
        assertThat(resultat.getMessages())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(Message.fromEnum(CODE_ERREUR_INATTENDUE, ID_SOURCE_LEGACY));
    }

    @Test
    void echec_au_lancement_de_B_le_legacy_est_quand_meme_appele() {
        when(async.listerProduitsB(any())).thenThrow(new TaskRejectedException("exécuteur saturé"));
        siLegacy(immediat(liste(List.of(produit("a1")), 1)));

        ListeProduits resultat = service.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).containsExactly(produit("a1"));
        assertThat(resultat.getMessages())
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(Message.fromEnum(CODE_ERREUR_INATTENDUE, ID_SOURCE_B));
    }

    // ---------- Les deux sources KO : 503 ----------

    @Test
    void les_deux_sources_en_timeout_technical_exception_dans_le_delai() {
        siB(jamais());
        siLegacy(jamais());

        long debut = System.nanoTime();
        assertThatThrownBy(() -> service.listerProduitsAvecCriteres(criteres))
                .isInstanceOf(TechnicalException.class)
                .hasCauseInstanceOf(TimeoutException.class);
        long dureeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - debut);

        assertThat(dureeMs).isBetween(TIMEOUT_MS - 20, TIMEOUT_MS + MARGE_MS);
    }

    @Test
    void les_deux_sources_en_echec_technical_exception_avec_la_vraie_cause() {
        siB(CompletableFuture.failedFuture(new CompletionException(new IllegalStateException("boom B"))));
        siLegacy(CompletableFuture.failedFuture(new IllegalArgumentException("boom legacy")));

        assertThatThrownBy(() -> service.listerProduitsAvecCriteres(criteres))
                .isInstanceOf(TechnicalException.class)
                .hasCauseInstanceOf(IllegalStateException.class) // CompletionException déballée
                .satisfies(ex -> assertThat(ex.getSuppressed())
                        .hasSize(1)
                        .hasOnlyElementsOfType(IllegalArgumentException.class));
    }

    @Test
    void B_en_timeout_et_legacy_rejete_au_lancement_technical_exception() {
        siB(jamais());
        when(async.listerProduitsA(any())).thenThrow(new TaskRejectedException("exécuteur saturé"));

        assertThatThrownBy(() -> service.listerProduitsAvecCriteres(criteres))
                .isInstanceOf(TechnicalException.class);
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
```

## 8. Test d'intégration (`@SpringJUnitConfig` + `@EnableAsync`)

Ce test emprunte le chemin réel : proxy `@Async`, puis vrai exécuteur à threads virtuels, puis services sources mockés avec un vrai `Thread.sleep`. Il vérifie que le bean injecté est bien un proxy AOP, et que les deux sources sont appelées sur des threads virtuels distincts du thread du test.

`AsyncProduitService` et `ProduitService` sont enregistrés tels quels, leurs constructeurs étant autowirés. **Si `ProduitService` a d'autres dépendances**, ajoute un `@Bean` mock pour chacune dans `Config`.

```java
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
                .containsExactly(Message.fromEnum(ProduitService.CODE_ERREUR_INATTENDUE, ID_SOURCE_B));
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
```

## 9. Vérification effectuée

- **Logique de `ProduitService` : vérifiée.** La classe, copiée sans modification, a été compilée en Java 21, sans avertissement, avec des shims pour les annotations, SLF4J et Lombok. Un harnais JDK pur a ensuite rejoué **15 scénarios (22 vérifications), tous OK**. Ils couvrent chaque ligne du § 4, les durées bornées (201 à 207 ms pour un timeout de 200 ms), l'ignorance d'une réponse tardive, le rejet au lancement de chaque côté, et un chemin équivalent au proxy `@Async` de Spring 6.1 (`supplyAsync` sur un exécuteur à threads virtuels, avec un `sleep` de 1 500 ms).
- **Tests JUnit/Spring des § 7 et 8 : non exécutés.** Ils ont été écrits mais pas lancés ici, car Maven Central n'était pas accessible depuis mon environnement. Ils sont à passer de ton côté. Les points sensibles à l'adaptation sont signalés : `produit(...)`, `Produit.equals` et les dépendances supplémentaires de `ProduitService`.

## 10. Limites connues et suites

- **Timeouts réseau des clients (étape suivante).**
  - Problème : tant que `RestTemplate` et surtout `WebServiceTemplate` n'ont pas de timeouts de connexion et de lecture, un legacy qui ne répond jamais garde son thread virtuel et sa connexion HTTP jusqu'à la coupure TCP. Les threads virtuels coûtent peu, mais les connexions du pool, elles, ne sont pas gratuites : en cas de dégradation durable, elles s'accumulent.
  - Recommandation : un timeout de lecture proche du timeout global, légèrement au-dessus, pour libérer les ressources peu après l'abandon de l'attente.
- **Contexte absent dans `handle` après un timeout.** Les `RequestAttributes`, le `SecurityContext` et le MDC sont absents. Si les logs reposent sur un `traceId` dans le MDC, les lignes « timeout » n'en auront pas. Si c'est gênant, on peut déplacer le log dans le thread de la requête, après `allOf`, au prix d'une durée moins précise pour les exceptions.
- **Résultats tardifs perdus.** Ils ne sont ni mis en cache ni réutilisés (le cache est hors périmètre).
- **Débranchement du legacy.** Il suffira de supprimer `brutLegacy`, `legacy` et `echecLegacy`. `allOf` et `fusionner` ne recevront plus que B, et la règle du 503 deviendra `if (echecB != null) throw ...`. `lancer`, `listeEnErreur` et `causeReelle` restent utiles tels quels.
- **Hors périmètre, non traités :** migration vers JDK 25 et Spring Boot, propagation par le `TaskDecorator`, Resilience4j et circuit breaker, pagination et tri.
