# Timeout sur la recherche multicritère `/ListerProducts`

## 1. Résumé de la demande

Dans `ProduitService.listerProduitsAvecCriteres`, A interroge en parallèle deux sources via `@Async` : B (REST) et le legacy (SOAP, nommé « A » dans le code). Aujourd'hui, deux défauts :

- **pas de timeout** : un legacy lent bloque toute la réponse ;
- **des `join()` successifs dans un seul `try/catch`** : l'échec d'un future fait perdre le résultat de l'autre.

L'objectif :

- un **timeout unique**, externalisé en propriété ;
- une **réponse partielle (200)** dès qu'une source renvoie des produits ;
- un **503** (`TechnicalException`) seulement quand **aucune** source interrogée ne permet de retour ;
- une option de filtre **`bSeulement`** : quand elle est vraie, seule B est interrogée et son échec donne un 503.

Le tout sans nouveau DTO et sans toucher à `AsyncProduitService`, aux services sources ni au handler d'erreurs.

## 2. Règles fonctionnelles retenues

- **Source « sans retour ».** Son `ListeProduits` a un `total` à 0 **et** au moins un message. Cela couvre trois cas :
  - une erreur interceptée par la source elle-même ;
  - un timeout ;
  - une exception non interceptée, ou un rejet au lancement.

  Cette règle repose sur le fait, confirmé, que les sources n'ajoutent **aucun message** quand une recherche ne trouve simplement rien.
- **Sources interrogées.** B toujours ; le legacy seulement si `bSeulement` est faux. En mode `bSeulement`, le legacy n'est **pas appelé du tout** (aucun thread consommé, aucun message le concernant).
- **503** si et seulement si **toutes les sources interrogées** sont sans retour :
  - mode normal : B **et** le legacy sans retour ;
  - mode `bSeulement` : B sans retour (erreur propre, timeout, exception ou rejet au lancement).
- **200** dans tous les autres cas :
  - liste fusionnée quand les deux sources répondent ;
  - réponse partielle avec le message de la source en erreur quand une seule répond ;
  - liste vide sans message quand la recherche est infructueuse.
- **Le timeout est une erreur technique comme une autre.** Il est porté par le nouveau code `ASYNC_TIMEOUT`. Les autres échecs d'appel asynchrone utilisent un code générique.

## 3. Hypothèses à confirmer

Chacune se change en un seul endroit.

| # | Sujet | Hypothèse | Où l'ajuster |
|---|---|---|---|
| H1 | Champs de `ListeProduits` | `produits`, `total`, `messages` (Lombok `@Data` / `@Builder`) ; le type d'élément `Produit` n'est nommé que dans les tests | accesseurs dans `fusionner`, `listeEnErreur`, `estSansRetour` |
| H2 | Identifiants de source | `ID_SOURCE_B = "B"`, `ID_SOURCE_LEGACY = "A"` | constantes de `ProduitService` |
| H3 | Code des échecs hors timeout, et du 503 | `ERREUR_TECHNIQUE_INATTENDUE` (placeholder pour ton code générique) | constante `CODE_ERREUR_APPEL_ASYNC` |
| H4 | Valeurs de `ASYNC_TIMEOUT` | `"<CODE>"` et `"<libellé> : %s"`, à fixer par toi | enum |
| H5 | Injection | Par constructeur, `@Value("${produits.recherche.timeout}") long` en millisecondes | constructeur (voir note § 6.3) |
| H6 | Visibilité | `listerProduitsAvecCriteres` passe de `private` à package-private, pour l'appeler depuis les tests | — |
| H7 | Outillage | SLF4J ; AssertJ, Mockito et JUnit ≥ 5.9 côté tests | — |
| H8 | Test d'intégration | Exécuteur à threads virtuels minimal redéclaré dans le test, sans `TaskDecorator` | classe `Config` du test |
| H9 | Ordre d'agrégation | B puis le legacy (concaténation) ; le tri éventuel reste là où il est | — |
| H10 | Accès à l'option | Champ `boolean bSeulement` de `CriteresFiltres`, getter Lombok `isBSeulement()` (voir l'alerte de nommage au § 12) | `listerProduitsAvecCriteres` |
| H11 | Timeout en mode `bSeulement` | Même valeur `produits.recherche.timeout` (règle du timeout unique) | voir § 12 |

## 4. Changements de comportement par rapport à l'existant

- **Une exception non interceptée dans une seule source ne fait plus échouer la requête.** Avant, une `RuntimeException` ou une `Error` était relancée telle quelle. Désormais, la source est sans retour : réponse partielle 200 avec un message technique, si l'autre source a répondu. Les `Error` sont incluses, et loguées avec leur stack trace.
- **Deux erreurs interceptées par les sources donnent désormais un 503.** Avant, deux sources renvoyant chacune leur propre erreur produisaient un 200 avec une liste vide et deux messages.
- **Mode `bSeulement` (nouveau).** Le legacy n'est plus appelé, et une B sans retour donne un 503 au lieu d'un 200 vide avec message.
- **Le 503 ne transporte ni les messages des sources ni la cause.** Le front reçoit l'erreur générique du handler. Le détail se trouve dans les logs :
  - le WARN de `listeEnErreur`, pour un timeout ou une exception ;
  - les logs des services sources, pour leurs propres erreurs.

## 5. Choix retenus

**`orTimeout` puis `handle`, regroupés dans `appelerSource`.** Chaque appel reçoit son `orTimeout`, puis un `handle` qui transforme tous les cas en `ListeProduits` :
- en cas de succès, la liste de la source, telle quelle ;
- en cas de timeout ou d'exception, une liste vide portant le message technique.

Le future obtenu ne peut plus échouer. `allOf(...).join()` attend donc les deux sources sans jamais lever : le défaut des `join()` successifs disparaît.

Avec l'arrivée de `bSeulement`, B est lancée dans deux branches. Le lancement, la conversion d'un rejet en `failedFuture`, l'`orTimeout` et le `handle` sont donc regroupés dans un seul helper, `appelerSource(appel, idSource)`, qui remplace `appelerEnFuture`. L'ordre `orTimeout` puis `handle` est ainsi garanti par construction, au lieu de reposer sur un commentaire répété à chaque appel. Le helper lit `timeoutMs` : c'est une méthode d'instance, et non plus `static`.

**Orchestration de `bSeulement` : une branche explicite.** B est lancée en premier dans tous les cas. Si `bSeulement` est vrai, on attend B seule et on décide aussitôt ; sinon, on lance le legacy et on suit le chemin à deux sources. J'ai préféré cette branche lisible à une version générique (liste de sources, `allMatch(estSansRetour)`) : avec deux sources, la généricité ajoute une indirection sans rien simplifier.

**Décision du 503 sur les `ListeProduits`, pas sur l'état des futures.** Avec la règle « total à 0 et au moins un message », un échec technique produit par `handle` et une erreur renvoyée par la source se reconnaissent de la même façon. Un seul critère, `estSansRetour`, couvre donc les deux. Pas besoin de garder une référence sur le future brut ni d'inspecter les causes.

**Pas de déballage d'exception.** Trois raisons :
- `handle` reçoit la `TimeoutException` directement, vérifié à l'exécution : un `instanceof` suffit à reconnaître le timeout ;
- pour les autres échecs, l'exception est loguée telle quelle, avec toute sa chaîne de `Caused by` ;
- la décision fonctionnelle n'a pas besoin de la cause.

**Timeout unique.** Les deux appels partent au même instant, le même délai appliqué à chacun équivaut donc à un timeout global. On sait en plus quelle source a dépassé, ce qu'un `orTimeout` posé sur `allOf` ne dirait pas. La valeur est un `long` en millisecondes, comme dans le YAML : pas d'ambiguïté de conversion `Duration`.

**Réutilisation de `ListeProduits` et `Message`.**
- Une source en échec est représentée par un `ListeProduits` vide portant `Message.fromEnum(code, idSource)`.
- La fusion construit **un nouvel objet** et copie les listes : on ne modifie jamais l'objet d'une source.
- Aucun record, DTO ou enum parallèle n'est introduit : uniquement des helpers privés dans `ProduitService`.

## 6. Code

### 6.1 `EnumCodeErreurTechnique` : constante ajoutée

```java
/** Une source n'a pas répondu dans le délai produits.recherche.timeout. Argument : identifiant de la source. */
ASYNC_TIMEOUT("<CODE>", "<libellé> : %s"),
```

### 6.2 `application.yaml`

```yaml
produits:
  recherche:
    # Timeout commun aux deux sources, en millisecondes, calé sur le legacy (la source la plus lente).
    timeout: 15000
```

### 6.3 `ProduitService`

Seuls les éléments ajoutés ou modifiés sont montrés. Les autres méthodes et dépendances de la classe restent en l'état. Le package `fr.exemple.produits` est fictif : adapte le package et les imports des classes métier, dans le code comme dans les tests.

> **Note sur le constructeur (H5).** Si la classe utilise `@RequiredArgsConstructor` de Lombok, `@Value` ne sera pas recopié sur le paramètre. Deux options : écrire le constructeur à la main comme ci-dessous, ou garder un champ non final `@Value("${produits.recherche.timeout}") private long timeoutMs;` et le renseigner dans les tests via `ReflectionTestUtils.setField(service, "timeoutMs", 200L)`.

```java
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
     * Interroge B, et le legacy sauf si {@code bSeulement}, avec un timeout commun.
     * Une source « sans retour » (total à 0 et au moins un message) n'empêche pas de renvoyer l'autre (HTTP 200).
     * Si toutes les sources interrogées sont sans retour : {@link TechnicalException} (HTTP 503).
     * Package-private (au lieu de private) pour les tests unitaires.
     */
    ListeProduits listerProduitsAvecCriteres(CriteresFiltres criteres) {
        CompletableFuture<ListeProduits> b =
                appelerSource(() -> asyncProduitService.listerProduitsB(criteres), ID_SOURCE_B);

        if (criteres.isBSeulement()) {
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
```

## 7. Comportement cas par cas

| B | Legacy | HTTP | Produits / total | Messages |
|---|---|---|---|---|
| répond | répond | 200 | B puis legacy / somme | messages de B puis messages du legacy |
| répond, aucun résultat, sans message | répond, aucun résultat, sans message | 200 | vide / 0 | aucun |
| renvoie sa propre erreur (total 0 + message) | répond | 200 | legacy | erreur de B |
| répond avec des produits **et** un message | sans retour | 200 | B | message de B + message de la source sans retour |
| répond | timeout | 200 | B / total de B | messages de B + `ASYNC_TIMEOUT("A")` |
| timeout | répond | 200 | legacy | `ASYNC_TIMEOUT("B")` + messages du legacy |
| répond | exception non interceptée **ou** rejet au lancement | 200 | B | messages de B + `CODE_ERREUR_APPEL_ASYNC("A")` |
| exception non interceptée **ou** rejet au lancement | répond | 200 | legacy | `CODE_ERREUR_APPEL_ASYNC("B")` + messages du legacy |
| sans retour (erreur propre, timeout ou exception) | sans retour | **503** | — | `TechnicalException(CODE_ERREUR_APPEL_ASYNC, "B", "A")` |

**Mode `bSeulement`** (le legacy n'est jamais appelé) :

| B | HTTP | Produits / total | Messages |
|---|---|---|---|
| répond (avec ou sans messages) | 200 | B / total de B | messages de B |
| répond, aucun résultat, sans message | 200 | vide / 0 | aucun |
| renvoie sa propre erreur (total 0 + message) | **503** | — | `TechnicalException(CODE_ERREUR_APPEL_ASYNC, "B")` |
| timeout | **503** | — | idem |
| exception non interceptée **ou** rejet au lancement | **503** | — | idem |

Le temps de réponse est borné par `produits.recherche.timeout`, à quelques millisecondes près.

Dans `TechnicalException`, les arguments sont `"B"` et `"A"`. Avec un template à un seul `%s`, `String.format` ignore le second : adapte le template si tu veux afficher les deux.

## 8. Points de vigilance

1. **L'ordre compte : `orTimeout` avant `handle`.** `orTimeout` fait échouer le future sur lequel il est appelé. Posé avant `handle`, il fait échouer le future de la source, et `handle` reçoit alors la `TimeoutException` et la convertit. Dans l'ordre inverse, c'est le future issu de `handle` qui échoue *après* le `handle` : la `TimeoutException` n'est plus interceptée et remonte par `allOf(...).join()`, ce qui recrée le problème initial.

2. **Le thread qui exécute `handle` varie.** Il y a trois cas :
   - le future est déjà terminé quand `handle` est attaché : thread de la requête ;
   - la source termine plus tard : thread virtuel de la source ;
   - timeout : thread `CompletableFutureDelayScheduler` (vérifié à l'exécution, voir § 11).

   Ce dernier est un thread démon **unique pour toute la JVM**, qui sert à tous les `orTimeout` et `delayedExecutor`. Il n'a pas le contexte du `TaskDecorator` : ni `RequestAttributes`, ni `SecurityContext`, ni MDC. C'est pourquoi `listeEnErreur` se limite à construire un message et à loguer : aucun appel bloquant, aucun accès au contexte de la requête. Un blocage à cet endroit retarderait tous les timeouts de l'application.

3. **Le timeout arrête l'attente, pas l'appel.** `orTimeout` ne fait que terminer le future. Même `cancel(true)` n'interrompt pas la tâche d'un `CompletableFuture`. L'appel SOAP continue donc sur son thread virtuel jusqu'à sa fin réseau. Son résultat tardif est ignoré : le `complete(...)` de Spring sur un future déjà terminé est sans effet. Couper l'appel lui-même relève des timeouts réseau des clients (§ 12).

4. **Le lancement peut échouer.** Avec un retour `CompletableFuture`, le proxy `@Async` soumet la tâche à l'exécuteur de façon synchrone. Un refus (`TaskRejectedException`) est donc levé **par l'appel lui-même**. Sans protection, un refus sur B empêcherait même de lancer le legacy. `appelerSource` le convertit en `failedFuture`, qui suit le même chemin qu'un échec ordinaire.

5. **Logs.** WARN avec l'identifiant de la source :
   - pour un timeout, **sans** stack trace (elle n'apporterait rien), avec la valeur du délai ;
   - pour un autre échec, **avec** stack trace, en passant l'exception en dernier argument SLF4J.

   Dans ce second cas, la première ligne peut afficher une `CompletionException`, enveloppe ajoutée par le proxy Spring. La vraie cause apparaît dans le `Caused by` qui suit.

6. **Pas de réutilisation après un timeout.** Après un timeout, `handle` ne voit jamais l'objet de la source : il construit une liste vide neuve. Et même en cas de succès, `fusionner` crée un nouvel objet et copie les listes. On ne détient donc jamais de référence partagée avec un thread source qui serait encore actif.

## 9. Tests unitaires (JUnit 5, sans contexte Spring)

`AsyncProduitService` est mocké : chaque test lui fait renvoyer un future piloté à la main. Le timeout est de 200 ms.

| Comportement simulé | Future utilisé |
|---|---|
| réponse immédiate | `completedFuture` |
| réponse tardive | `supplyAsync` + `delayedExecutor` |
| jamais de réponse | `new CompletableFuture<>()` |
| future en échec | `failedFuture` |
| échec au lancement | `thenThrow(new TaskRejectedException(...))` |

Les tests du mode `bSeulement` vérifient en plus, avec `verify(async, never()).listerProduitsA(any())`, que le legacy n'est jamais appelé.

Le `@Timeout` de classe tourne en `SEPARATE_THREAD`, car `join()` n'est pas interruptible : un test bloqué échoue donc au lieu de bloquer la build. Les bornes de durée gardent une marge de 300 ms pour les CI chargées.

> **À adapter au code réel :**
> - la fabrique `produit(id)` et les `containsExactly(produit(...))` supposent que `Produit` a un constructeur à un argument et un `equals`, ce que fournit Lombok `@Data` ;
> - les constantes `EnumCodeErreurFonctionnel.AVERTISSEMENT_FONCTIONNEL` et `EnumCodeErreurTechnique.ERREUR_APPEL_SOURCE` sont fictives : remplace-les par des codes existants.
>
> Les messages sont comparés champ par champ (`usingRecursiveFieldByFieldElementComparator`), ce qui ne dépend pas de l'existence d'un `equals` sur `Message`.

```java
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
```

## 10. Test d'intégration (`@SpringJUnitConfig` + `@EnableAsync`)

Ce test emprunte le chemin réel : proxy `@Async`, puis vrai exécuteur à threads virtuels, puis services sources mockés avec un vrai `Thread.sleep`. Il vérifie que le bean injecté est bien un proxy AOP, et que les deux sources sont appelées sur des threads virtuels distincts du thread du test.

Deux cas couvrent le mode `bSeulement` : B lente (503 dans le délai, legacy jamais appelé) et B rapide (résultats de B, un seul appel, sur un thread virtuel).

`AsyncProduitService` et `ProduitService` sont enregistrés tels quels, leurs constructeurs étant autowirés. **Si `ProduitService` a d'autres dépendances**, ajoute un `@Bean` mock pour chacune dans `Config`.

```java
package fr.exemple.produits;

import static fr.exemple.produits.ProduitService.ID_SOURCE_B;
import static fr.exemple.produits.ProduitService.ID_SOURCE_LEGACY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
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

    @Test
    void bSeulement_B_lent_technical_exception_dans_le_delai_et_legacy_jamais_appele() {
        when(produitBService.listerProduits(any())).thenAnswer(inv -> repondre(LENT_MS, liste(new Produit("b1"))));
        CriteresFiltres criteres = new CriteresFiltres();
        criteres.setBSeulement(true);

        long debut = System.nanoTime();
        assertThatThrownBy(() -> produitService.listerProduitsAvecCriteres(criteres))
                .isInstanceOf(TechnicalException.class);
        long dureeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - debut);

        assertThat(dureeMs).isBetween(TIMEOUT_MS - 20, TIMEOUT_MS + MARGE_MS);
        verify(produitAService, never()).listerProduits(any());
    }

    @Test
    void bSeulement_B_rapide_resultats_de_B_via_le_proxy_async() {
        when(produitBService.listerProduits(any())).thenAnswer(inv -> repondre(50, liste(new Produit("b1"))));
        CriteresFiltres criteres = new CriteresFiltres();
        criteres.setBSeulement(true);

        ListeProduits resultat = produitService.listerProduitsAvecCriteres(criteres);

        assertThat(resultat.getProduits()).containsExactly(new Produit("b1"));
        assertThat(resultat.getMessages()).isEmpty();
        assertThat(threadsAppels).singleElement().matches(Thread::isVirtual, "thread virtuel");
        verify(produitAService, never()).listerProduits(any());
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

## 11. Vérification effectuée

- **Logique de `ProduitService` : vérifiée.** La classe, copiée sans modification, a été compilée en Java 21, sans avertissement, avec des shims pour les annotations, SLF4J et Lombok. Un harnais JDK pur a ensuite rejoué **26 scénarios (30 vérifications), tous OK**. Ils couvrent :
  - chaque ligne des deux tableaux du § 7, mode `bSeulement` compris, avec un compteur vérifiant que le legacy n'est pas appelé dans ce mode ;
  - les durées bornées (201 à 213 ms pour un timeout de 200 ms) ;
  - l'ignorance d'une réponse tardive ;
  - le rejet au lancement de chaque côté ;
  - un chemin équivalent au proxy `@Async` de Spring 6.1 (`supplyAsync` sur un exécuteur à threads virtuels, avec un `sleep` de 1 500 ms).
- **Tests JUnit/Spring des § 9 et 10 : non exécutés.** Ils ont été écrits mais pas lancés ici, car Maven Central n'était pas accessible depuis mon environnement. Ils sont à passer de ton côté. Les points d'adaptation sont signalés au § 9 et au § 10.

## 12. Limites connues et suites

- **Nommage `bSeulement` : risque de binding.** Une propriété dont le nom commence par une seule minuscule suivie d'une majuscule pose un problème connu. Lombok génère `isBSeulement()` / `setBSeulement()`, et la convention JavaBeans en déduit la propriété **`BSeulement`** (vérifié avec `java.beans.Introspector`). Jackson, lui, en déduit **`bseulement`**. Selon la façon dont `CriteresFiltres` est alimenté, un paramètre de requête ou un champ JSON nommé `bSeulement` risque donc d'être **ignoré silencieusement**, et le filtre resterait à `false`. Deux parades :
  - renommer le champ, par exemple `seulementB` ou `sourceBUniquement`, ce qui est le plus simple ;
  - ou garder le nom et forcer le nom exposé (`@JsonProperty("bSeulement")` pour du JSON), puis le vérifier par un test du contrôleur.
- **Timeout en mode `bSeulement`.** La valeur unique est calée sur le legacy (15 s). En mode B seul, un B bloqué fait donc attendre 15 s avant le 503, alors que B est normalement bien plus rapide. Si c'est gênant, une seconde propriété dédiée à ce mode est l'évolution naturelle. Je ne l'ai pas ajoutée, pour respecter la règle du timeout unique.
- **Timeouts réseau des clients (étape suivante).**
  - Problème : tant que `RestTemplate` et surtout `WebServiceTemplate` n'ont pas de timeouts de connexion et de lecture, un legacy qui ne répond jamais garde son thread virtuel et sa connexion HTTP jusqu'à la coupure TCP. Les threads virtuels coûtent peu, mais les connexions du pool, elles, ne sont pas gratuites : en cas de dégradation durable, elles s'accumulent.
  - Recommandation : un timeout de lecture proche du timeout global, légèrement au-dessus, pour libérer les ressources peu après l'abandon de l'attente.
- **Contexte absent dans `handle` après un timeout.** Les `RequestAttributes`, le `SecurityContext` et le MDC sont absents. Si les logs reposent sur un `traceId` dans le MDC, la ligne « timeout » n'en aura pas, ce qui complique son rapprochement avec le 503 correspondant. Si c'est gênant, on peut déplacer le log dans le thread de la requête, après `allOf`.
- **Dépendance à une convention des sources.** La règle « sans retour » suppose que les sources n'ajoutent jamais de message pour une simple recherche infructueuse. Si cela change un jour, une double recherche infructueuse produira un 503. Il faudra alors restreindre `estSansRetour` aux messages techniques.
- **Résultats tardifs perdus.** Ils ne sont ni mis en cache ni réutilisés (le cache est hors périmètre).
- **Débranchement du legacy.** La branche `bSeulement` *est* déjà le comportement cible. Il suffira de ne garder qu'elle : supprimer le test de `bSeulement` et tout ce qui suit (lancement du legacy, `allOf`, double condition). L'option `bSeulement` deviendra alors sans objet. Les helpers restent utiles tels quels.
- **Hors périmètre, non traités :** migration vers JDK 25 et Spring Boot, propagation par le `TaskDecorator`, Resilience4j et circuit breaker, pagination et tri.
