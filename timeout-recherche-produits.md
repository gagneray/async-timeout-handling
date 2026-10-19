# Timeout et résultats partiels sur `/ListerProducts`

Orchestration des appels parallèles B (REST) et C (legacy SOAP) dans le micro-service A.

---

## 1. Résumé de la demande

**Contexte.** L'endpoint `/ListerProducts` de A appelle en parallèle deux sources, via deux méthodes `@Async` exécutées sur des threads virtuels :

- **B**, un micro-service REST (client `RestTemplate`) ;
- **C**, un web service legacy SOAP (client `WebServiceTemplate`), plus lent que B, et qui sera débranché à terme.

Les deux listes sont agrégées en une seule réponse.

**Problèmes du code actuel.**

- Aucun timeout : un C lent bloque toute la réponse.
- Les `join()` successifs dans un seul `try/catch` font qu'une erreur sur une source fait perdre le résultat de l'autre.

**Objectif de ce lot.** Obtenir une solution simple, testable rapidement, qui :

1. borne le temps d'attente ;
2. renvoie les produits de la source qui a répondu, si l'autre est en erreur ou en timeout ;
3. indique au BFF qu'une source manque, et laquelle, pour que le front affiche un message ;
4. gère le cas où les deux sources sont KO.

**Hors périmètre de ce lot.**

- La migration JDK 25 / Spring Boot.
- Les timeouts réseau des clients `RestTemplate` / `WebServiceTemplate` (étape suivante, voir § 8).
- Le format du corps d'erreur (géré de votre côté).
- La propagation des `RequestAttributes`.
- La pagination, le tri et les erreurs fonctionnelles.

---

## 2. Choix retenus

| Sujet | Choix | Pourquoi |
|---|---|---|
| Mécanisme d'orchestration | `orTimeout(...)` puis `handle(...)` sur chaque future, puis `allOf(...).join()` | Chaque source devient un résultat qui ne peut pas échouer ; aucune exception vérifiée ; la source en cause est connue sans ambiguïté |
| Valeur du timeout | **Une seule valeur** (`produits.recherche.timeout`), calée sur le legacy et appliquée aux deux futures | Les deux appels partent au même instant : une même valeur sur chaque future revient exactement à un timeout global, tout en sachant quelle source a dépassé |
| Les deux sources OK | **HTTP 200**, `complet = true` | Cas nominal |
| Une seule source OK | **HTTP 200**, `complet = false`, et l'anomalie de l'autre source | La recherche a fonctionné, mais de façon incomplète : le front affiche les produits et un avertissement |
| Aucune source OK | **HTTP 503 Service Unavailable** | Voir le détail ci-dessous |
| Indicateur dans la réponse | Deux champs ajoutés au DTO : `complet` et `anomalies` (source + type `TIMEOUT` / `ERREUR`) | La solution la plus simple ; rétrocompatible pour un BFF qui ignore les champs inconnus |
| Classification des erreurs | `TIMEOUT` si la chaîne de causes contient une `TimeoutException`, une `SocketTimeoutException` ou une `HttpTimeoutException` ; sinon `ERREUR` | Couvre à la fois le timeout d'orchestration et le futur timeout réseau des clients |
| Méthodes `@Async` existantes | **Conservées telles quelles** (seul `listerProduitsA` est renommé en `listerProduitsC`) | Changement minimal : seul `ProduitService` est réécrit |

**Pourquoi 503 quand aucune source ne répond, et pas les autres options.**

- **200 avec une liste vide** voudrait dire « la recherche a fonctionné et n'a rien trouvé ». C'est faux, et le front afficherait « aucun résultat » au lieu d'une erreur.
- **500** voudrait dire « A a un bug ». Or A fonctionne : ce sont ses dépendances qui sont indisponibles. Garder le 500 pour les vrais bugs permet de les distinguer dans le monitoring.
- **503** dit exactement « service momentanément indisponible, réessayez plus tard ». C'est l'information utile pour le BFF et pour le front.

---

## 3. Comportement attendu

Exemple avec un timeout T = 10 s.

| B | C | HTTP | `produits` | `complet` | `anomalies` | Temps de réponse |
|---|---|---|---|---|---|---|
| OK | OK | 200 | B puis C | `true` | `[]` | durée de l'appel le plus lent |
| OK | timeout | 200 | B | `false` | `[{C, TIMEOUT}]` | ≈ T |
| OK | erreur | 200 | B | `false` | `[{C, ERREUR}]` | durée de B ou de C (la plus longue) |
| timeout | OK | 200 | C | `false` | `[{B, TIMEOUT}]` | ≈ T |
| erreur | OK | 200 | C | `false` | `[{B, ERREUR}]` | durée de B ou de C (la plus longue) |
| KO | KO | 503 | — | — | portées par l'exception | ≤ T |

Le temps de réponse ne dépasse jamais T, à quelques millisecondes près.

---

## 4. Rappel : fonctionnement de `orTimeout(...).handle(...)`

**`orTimeout(timeout, unit)`** (Java 9+)
- Si le future n'est pas terminé à l'échéance, il est terminé en échec avec une `TimeoutException`.
- La méthode modifie le future sur lequel on l'appelle et le renvoie.

**`handle((resultat, erreur) -> ...)`**
- S'exécute dans tous les cas, que le future se termine en succès ou en échec.
- Exactement un des deux arguments est significatif : `(valeur, null)` en cas de succès, `(null, exception)` en cas d'échec.
- La valeur renvoyée termine le nouveau stage **normalement**. L'erreur est ainsi transformée en donnée (`ResultatSource`).

**Ce que cela garantit.** Chaque branche se termine toujours normalement, donc `allOf(...)` aussi, et `join()` ne lève pas. Une erreur de B ne peut plus faire perdre le résultat de C.

**Points de vigilance.**

1. **L'ordre des appels compte.** Il faut écrire `orTimeout` *puis* `handle`. Dans l'ordre inverse, la `TimeoutException` n'est pas interceptée.
2. **Le thread du `handle` varie.** Après un timeout, `handle` s'exécute sur un thread interne unique du JDK, partagé par toute la JVM. Il doit rester trivial : mapping uniquement, pas d'appel réseau ni de traitement lourd.
3. **Le timeout n'arrête pas l'appel en cours.** Il arrête seulement l'*attente*. L'appel à C continue sur son thread virtuel jusqu'à sa fin naturelle. Les timeouts réseau des clients (§ 8) sont nécessaires pour le couper réellement.
4. **Si le mapping lui-même plante**, la branche redevient en échec, et `join()` lève une exception. On obtient alors un 500, ce qui est le bon signal pour un bug dans A.

---

## 5. Code

### 5.1 Fichiers

```
com.exemple.produits
├── ProduitService.java                ← réécrit (orchestration)
├── AsyncProduitService.java           ← inchangé (listerProduitsA renommé listerProduitsC)
├── SourcesIndisponiblesException.java ← nouveau (503)
├── ProduitController.java             ← type de retour adapté
├── source/ResultatSource.java         ← nouveau (résultat interne d'une source)
└── dto/
    ├── RechercheProduitsResponse.java ← nouveau, ou champs ajoutés à votre DTO actuel
    ├── AnomalieSource.java            ← nouveau
    ├── SourceProduit.java             ← nouveau (enum B, C)
    └── TypeAnomalie.java              ← nouveau (enum TIMEOUT, ERREUR)
```

`Produit`, `ListeProduits`, `CriteresFiltres`, `ProduitBService` et `ProduitCService` sont les vôtres. Dans ce document, `ListeProduits` est supposé exposer un accesseur `produits()` qui renvoie une `List<Produit>` : adaptez l'accesseur à votre classe.

### 5.2 Configuration

```yaml
# application.yml
produits:
  recherche:
    timeout: 10s   # calé sur le legacy C ; valeur à ajuster plus tard
```

### 5.3 DTO de réponse

Si votre DTO de réponse actuel est déjà un objet JSON (et non un tableau à la racine), le plus simple est d'**ajouter** les champs `complet` et `anomalies` à ce DTO existant, plutôt que de créer `RechercheProduitsResponse`. La forme JSON actuelle reste ainsi inchangée pour le BFF.

```java
package com.exemple.produits.dto;

import com.exemple.produits.Produit;

import java.util.List;

/**
 * Réponse de /ListerProducts.
 * complet = false dès qu'une source manque ; anomalies indique laquelle et pourquoi (vide si complet).
 */
public record RechercheProduitsResponse(
        List<Produit> produits,
        boolean complet,
        List<AnomalieSource> anomalies) {
}
```

```java
package com.exemple.produits.dto;

/** Source qui n'a pas pu être prise en compte, et pourquoi. Jamais de message technique ici. */
public record AnomalieSource(SourceProduit source, TypeAnomalie type) {
}
```

```java
package com.exemple.produits.dto;

/** Noms logiques exposés au BFF (à renommer, ex. CATALOGUE / LEGACY). Jamais un hôte ou une URL. */
public enum SourceProduit { B, C }
```

```java
package com.exemple.produits.dto;

public enum TypeAnomalie { TIMEOUT, ERREUR }
```

Exemples de réponses JSON :

```json
// 200 — les deux sources ont répondu
{ "produits": [ { "id": "B1", "libelle": "..." }, { "id": "C1", "libelle": "..." } ],
  "complet": true,
  "anomalies": [] }

// 200 — C en timeout : le front affiche les produits de B et un avertissement
{ "produits": [ { "id": "B1", "libelle": "..." } ],
  "complet": false,
  "anomalies": [ { "source": "C", "type": "TIMEOUT" } ] }
```

Côté BFF, il suffit d'ajouter les deux champs au DTO miroir et de les transmettre au front. C'est le front qui choisit le message à afficher à partir du couple (`source`, `type`).

Si le BFF utilise l'`ObjectMapper` de Spring Boot, les champs inconnus sont ignorés par défaut. Une version du BFF qui n'a pas encore les nouveaux champs continue donc de fonctionner.

### 5.4 `ResultatSource` : le résultat interne d'une source

```java
package com.exemple.produits.source;

import com.exemple.produits.ListeProduits;
import com.exemple.produits.Produit;
import com.exemple.produits.dto.SourceProduit;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Résultat d'un appel à UNE source, quel que soit son issue.
 * Un échec ou un timeout est porté ici comme une donnée : il n'est jamais levé.
 */
public record ResultatSource(
        SourceProduit source,
        Statut statut,
        List<Produit> produits,   // vide si statut != OK
        Throwable cause,          // null si statut == OK
        Duration duree) {

    public enum Statut { OK, TIMEOUT, ERREUR }

    /**
     * Construit le résultat à partir des deux arguments reçus par CompletableFuture.handle(...) :
     * exactement un des deux est significatif (liste si succès, erreur si échec).
     */
    public static ResultatSource depuis(SourceProduit source, ListeProduits liste, Throwable erreur, Duration duree) {
        if (erreur == null) {
            List<Produit> produits = (liste == null || liste.produits() == null) ? List.of() : liste.produits();
            return new ResultatSource(source, Statut.OK, produits, null, duree);
        }
        Throwable cause = causeRacine(erreur);
        Statut statut = estUnTimeout(cause) ? Statut.TIMEOUT : Statut.ERREUR;
        return new ResultatSource(source, statut, List.of(), cause, duree);
    }

    public boolean estOk() {
        return statut == Statut.OK;
    }

    /** Retire les enveloppes CompletionException / ExecutionException ajoutées par CompletableFuture et @Async. */
    static Throwable causeRacine(Throwable ex) {
        Throwable t = ex;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /**
     * Timeout d'orchestration (TimeoutException levée par orTimeout) ou timeout réseau du client
     * (SocketTimeoutException enveloppée par RestTemplate / WebServiceTemplate), recherché dans toute la chaîne de causes.
     */
    static boolean estUnTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof TimeoutException
                    || c instanceof SocketTimeoutException
                    || c instanceof HttpTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
```

### 5.5 `ProduitService` : l'orchestration

```java
package com.exemple.produits;

import com.exemple.produits.dto.AnomalieSource;
import com.exemple.produits.dto.RechercheProduitsResponse;
import com.exemple.produits.dto.SourceProduit;
import com.exemple.produits.dto.TypeAnomalie;
import com.exemple.produits.source.ResultatSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Service
public class ProduitService {

    private static final Logger log = LoggerFactory.getLogger(ProduitService.class);

    private final AsyncProduitService asyncProduitService;
    private final Duration timeout;

    public ProduitService(AsyncProduitService asyncProduitService,
                          @Value("${produits.recherche.timeout:10s}") Duration timeout) {
        this.asyncProduitService = Objects.requireNonNull(asyncProduitService);
        this.timeout = Objects.requireNonNull(timeout);
    }

    /**
     * Interroge B et C en parallèle et agrège les résultats.
     * <ul>
     *   <li>B et C OK : réponse complète (complet = true).</li>
     *   <li>Une seule source OK : réponse partielle (complet = false + anomalie de l'autre source).</li>
     *   <li>Aucune source OK : SourcesIndisponiblesException (HTTP 503).</li>
     * </ul>
     * Le temps d'attente maximal est d'environ {@code timeout}, quelle que soit la source lente.
     */
    public RechercheProduitsResponse listerProduitsAvecCriteres(CriteresFiltres criteres) {
        CompletableFuture<ResultatSource> futureB =
                appeler(SourceProduit.B, () -> asyncProduitService.listerProduitsB(criteres));
        CompletableFuture<ResultatSource> futureC =
                appeler(SourceProduit.C, () -> asyncProduitService.listerProduitsC(criteres));

        // Ne lève pas : chaque branche se termine toujours normalement grâce à handle(...).
        CompletableFuture.allOf(futureB, futureC).join();

        // Les deux futures sont terminés : join() rend la main immédiatement.
        return agreger(List.of(futureB.join(), futureC.join()));
    }

    /**
     * Lance l'appel à une source et le transforme en future qui ne peut pas échouer :
     * succès, erreur ou timeout deviennent un ResultatSource.
     */
    private CompletableFuture<ResultatSource> appeler(SourceProduit source,
                                                      Supplier<CompletableFuture<ListeProduits>> appel) {
        long debut = System.nanoTime();
        CompletableFuture<ListeProduits> future;
        try {
            future = appel.get();
        } catch (RuntimeException e) {
            // Échec au lancement (ex. TaskRejectedException si l'exécuteur refuse la tâche).
            future = CompletableFuture.failedFuture(e);
        }
        // Ordre important : orTimeout AVANT handle, sinon la TimeoutException n'est pas interceptée.
        return future
                .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .handle((liste, erreur) -> ResultatSource.depuis(
                        source, liste, erreur, Duration.ofNanos(System.nanoTime() - debut)));
    }

    private RechercheProduitsResponse agreger(List<ResultatSource> resultats) {
        resultats.forEach(this::journaliser);

        List<AnomalieSource> anomalies = resultats.stream()
                .filter(r -> !r.estOk())
                .map(r -> new AnomalieSource(r.source(), versTypeAnomalie(r.statut())))
                .toList();

        if (anomalies.size() == resultats.size()) {
            throw new SourcesIndisponiblesException(anomalies);
        }

        List<Produit> produits = resultats.stream()
                .filter(ResultatSource::estOk)
                .flatMap(r -> r.produits().stream())
                .toList();

        return new RechercheProduitsResponse(produits, anomalies.isEmpty(), anomalies);
    }

    private static TypeAnomalie versTypeAnomalie(ResultatSource.Statut statut) {
        return switch (statut) {
            case TIMEOUT -> TypeAnomalie.TIMEOUT;
            case ERREUR -> TypeAnomalie.ERREUR;
            case OK -> throw new IllegalArgumentException("Une source OK n'est pas une anomalie");
        };
    }

    private void journaliser(ResultatSource r) {
        long ms = r.duree().toMillis();
        switch (r.statut()) {
            case OK -> log.debug("Source {} OK : {} produit(s) en {} ms", r.source(), r.produits().size(), ms);
            // Timeout : pas de stack trace, l'information utile est la source et la durée.
            case TIMEOUT -> log.warn("Source {} en timeout après {} ms (seuil {} ms) : {}",
                    r.source(), ms, timeout.toMillis(), r.cause().toString());
            // Erreur : stack trace conservée pour l'analyse (dernier argument = Throwable).
            case ERREUR -> log.warn("Source {} en erreur après {} ms", r.source(), ms, r.cause());
        }
    }
}
```

### 5.6 `AsyncProduitService` (inchangé, à un renommage près)

```java
package com.exemple.produits;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Inchangé par rapport à l'existant, à un renommage près : listerProduitsA devient listerProduitsC (le legacy SOAP).
 * Si produitXService.listerProduits lève une exception, Spring renvoie un future en échec (cause enveloppée).
 */
@Service
public class AsyncProduitService {

    private final ProduitBService produitBService;
    private final ProduitCService produitCService;

    public AsyncProduitService(ProduitBService produitBService, ProduitCService produitCService) {
        this.produitBService = produitBService;
        this.produitCService = produitCService;
    }

    @Async("taskExecutor")
    public CompletableFuture<ListeProduits> listerProduitsB(CriteresFiltres criteres) {
        return CompletableFuture.completedFuture(produitBService.listerProduits(criteres));
    }

    @Async("taskExecutor")
    public CompletableFuture<ListeProduits> listerProduitsC(CriteresFiltres criteres) {
        return CompletableFuture.completedFuture(produitCService.listerProduits(criteres));
    }
}
```

### 5.7 `SourcesIndisponiblesException` (HTTP 503)

```java
package com.exemple.produits;

import com.exemple.produits.dto.AnomalieSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.List;

/**
 * Levée quand aucune source n'a répondu correctement dans le temps imparti.
 * Mappée en HTTP 503 ; les anomalies restent disponibles pour le handler d'erreurs maison.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class SourcesIndisponiblesException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<AnomalieSource> anomalies;

    public SourcesIndisponiblesException(List<AnomalieSource> anomalies) {
        super("Aucune source de produits n'a répondu : " + anomalies);
        this.anomalies = List.copyOf(anomalies);
    }

    public List<AnomalieSource> getAnomalies() {
        return anomalies;
    }
}
```

L'annotation `@ResponseStatus` suffit pour obtenir un 503. Attention toutefois : si un `@RestControllerAdvice` existant intercepte `Exception` ou `RuntimeException` de façon générique, il passera avant cette annotation et renverra un 500. Dans ce cas, ajoutez un `@ExceptionHandler(SourcesIndisponiblesException.class)` dans votre format d'erreur ; `getAnomalies()` y est disponible.

### 5.8 Contrôleur

```java
package com.exemple.produits;

import com.exemple.produits.dto.RechercheProduitsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProduitController {

    private final ProduitService produitService;

    public ProduitController(ProduitService produitService) {
        this.produitService = produitService;
    }

    /** 200 (complet ou partiel) ; 503 via SourcesIndisponiblesException si aucune source n'a répondu. */
    @GetMapping("/ListerProducts")
    public RechercheProduitsResponse listerProduits(CriteresFiltres criteres) {
        return produitService.listerProduitsAvecCriteres(criteres);
    }
}
```

---

## 6. Tests

### 6.1 Tests unitaires : `ProduitServiceTest`

Ces tests n'utilisent ni Spring ni Mockito, seulement JUnit 5. Les futures sont pilotés à la main : réponse immédiate, réponse tardive, jamais de réponse, échec. Le timeout est fixé à 200 ms pour que la suite s'exécute en environ une seconde.

```java
package com.exemple.produits;

import com.exemple.produits.dto.AnomalieSource;
import com.exemple.produits.dto.RechercheProduitsResponse;
import com.exemple.produits.dto.SourceProduit;
import com.exemple.produits.dto.TypeAnomalie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests unitaires de l'orchestration, sans Spring ni Mockito.
 * Les futures sont pilotés à la main : réponse immédiate, réponse tardive, jamais de réponse, échec.
 */
class ProduitServiceTest {

    private static final Duration TIMEOUT = Duration.ofMillis(200);
    private static final CriteresFiltres CRITERES = new CriteresFiltres("vélo");

    private static final Produit P_B1 = new Produit("B1", "Produit B1");
    private static final Produit P_B2 = new Produit("B2", "Produit B2");
    private static final Produit P_C1 = new Produit("C1", "Produit C1");

    private AsyncProduitServiceSimule async;
    private ProduitService service;

    @BeforeEach
    void setUp() {
        async = new AsyncProduitServiceSimule();
        service = new ProduitService(async, TIMEOUT);
    }

    @Test
    void deuxSourcesOk_reponseComplete_produitsDeBPuisC() {
        async.b = () -> repond(P_B1, P_B2);
        async.c = () -> repond(P_C1);

        RechercheProduitsResponse r = service.listerProduitsAvecCriteres(CRITERES);

        assertTrue(r.complet());
        assertEquals(List.of(P_B1, P_B2, P_C1), r.produits());
        assertEquals(List.of(), r.anomalies());
    }

    @Test
    void cTropLent_retourneLesProduitsDeB_avecIndicateurTimeout_sansAttendreC() {
        async.b = () -> repond(P_B1);
        async.c = () -> repondApres(Duration.ofSeconds(2), P_C1);   // 10 fois le timeout

        long debut = System.nanoTime();
        RechercheProduitsResponse r = service.listerProduitsAvecCriteres(CRITERES);
        long dureeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - debut);

        assertFalse(r.complet());
        assertEquals(List.of(P_B1), r.produits());
        assertEquals(List.of(new AnomalieSource(SourceProduit.C, TypeAnomalie.TIMEOUT)), r.anomalies());
        assertTrue(dureeMs < TIMEOUT.toMillis() + 300, "attente bornée par le timeout, mesurée : " + dureeMs + " ms");
    }

    @Test
    void bNeRepondJamais_retourneLesProduitsDeC_avecIndicateurTimeout() {
        async.b = ProduitServiceTest::neRepondJamais;
        async.c = () -> repond(P_C1);

        RechercheProduitsResponse r = service.listerProduitsAvecCriteres(CRITERES);

        assertFalse(r.complet());
        assertEquals(List.of(P_C1), r.produits());
        assertEquals(List.of(new AnomalieSource(SourceProduit.B, TypeAnomalie.TIMEOUT)), r.anomalies());
    }

    @Test
    void bEnErreur_retourneLesProduitsDeC_avecIndicateurErreur() {
        async.b = () -> echoue(new IllegalStateException("HTTP 500 sur B"));
        async.c = () -> repond(P_C1);

        RechercheProduitsResponse r = service.listerProduitsAvecCriteres(CRITERES);

        assertFalse(r.complet());
        assertEquals(List.of(P_C1), r.produits());
        assertEquals(List.of(new AnomalieSource(SourceProduit.B, TypeAnomalie.ERREUR)), r.anomalies());
    }

    @Test
    void timeoutReseauDuClient_estClasseTimeout() {
        // Ce que renvoie RestTemplate (ResourceAccessException) ou WebServiceTemplate (WebServiceIOException)
        // quand le read timeout du client se déclenche : une SocketTimeoutException en cause.
        RuntimeException exceptionClient = new RuntimeException("I/O error", new SocketTimeoutException("Read timed out"));
        async.b = () -> repond(P_B1);
        async.c = () -> echoue(exceptionClient);

        RechercheProduitsResponse r = service.listerProduitsAvecCriteres(CRITERES);

        assertEquals(List.of(new AnomalieSource(SourceProduit.C, TypeAnomalie.TIMEOUT)), r.anomalies());
    }

    @Test
    void echecAuLancementDeLaTache_estClasseErreur() {
        // Ex. TaskRejectedException levée par le proxy @Async avant même de renvoyer un future.
        async.b = () -> { throw new IllegalStateException("tâche refusée par l'exécuteur"); };
        async.c = () -> repond(P_C1);

        RechercheProduitsResponse r = service.listerProduitsAvecCriteres(CRITERES);

        assertEquals(List.of(P_C1), r.produits());
        assertEquals(List.of(new AnomalieSource(SourceProduit.B, TypeAnomalie.ERREUR)), r.anomalies());
    }

    @Test
    void sourceOkSansProduit_resteUneReponseComplete() {
        async.b = () -> CompletableFuture.completedFuture(null);
        async.c = () -> repond();

        RechercheProduitsResponse r = service.listerProduitsAvecCriteres(CRITERES);

        assertTrue(r.complet());
        assertEquals(List.of(), r.produits());
    }

    @Test
    void deuxSourcesKo_leveSourcesIndisponibles_avecLesDeuxAnomalies() {
        async.b = () -> echoue(new IllegalStateException("HTTP 500 sur B"));
        async.c = ProduitServiceTest::neRepondJamais;

        SourcesIndisponiblesException e = assertThrows(SourcesIndisponiblesException.class,
                () -> service.listerProduitsAvecCriteres(CRITERES));

        assertEquals(List.of(
                new AnomalieSource(SourceProduit.B, TypeAnomalie.ERREUR),
                new AnomalieSource(SourceProduit.C, TypeAnomalie.TIMEOUT)), e.getAnomalies());
    }

    @Test
    void deuxSourcesEnTimeout_leveSourcesIndisponibles() {
        async.b = ProduitServiceTest::neRepondJamais;
        async.c = ProduitServiceTest::neRepondJamais;

        SourcesIndisponiblesException e = assertThrows(SourcesIndisponiblesException.class,
                () -> service.listerProduitsAvecCriteres(CRITERES));

        assertEquals(2, e.getAnomalies().size());
        assertTrue(e.getAnomalies().stream().allMatch(a -> a.type() == TypeAnomalie.TIMEOUT));
    }

    // --- Outils de simulation -------------------------------------------------------------

    private static CompletableFuture<ListeProduits> repond(Produit... produits) {
        return CompletableFuture.completedFuture(new ListeProduits(List.of(produits)));
    }

    private static CompletableFuture<ListeProduits> repondApres(Duration delai, Produit... produits) {
        return CompletableFuture.supplyAsync(() -> new ListeProduits(List.of(produits)),
                CompletableFuture.delayedExecutor(delai.toMillis(), TimeUnit.MILLISECONDS));
    }

    private static CompletableFuture<ListeProduits> neRepondJamais() {
        return new CompletableFuture<>();
    }

    /** Reproduit ce que fait le proxy @Async quand la méthode lève : future en échec, cause enveloppée. */
    private static CompletableFuture<ListeProduits> echoue(Throwable cause) {
        return CompletableFuture.failedFuture(new CompletionException(cause));
    }

    /** Remplace les deux méthodes @Async par des futures pilotés par le test. */
    static class AsyncProduitServiceSimule extends AsyncProduitService {
        Supplier<CompletableFuture<ListeProduits>> b;
        Supplier<CompletableFuture<ListeProduits>> c;

        AsyncProduitServiceSimule() {
            super(null, null);
        }

        @Override
        public CompletableFuture<ListeProduits> listerProduitsB(CriteresFiltres criteres) {
            return b.get();
        }

        @Override
        public CompletableFuture<ListeProduits> listerProduitsC(CriteresFiltres criteres) {
            return c.get();
        }
    }
}
```

### 6.2 Test d'intégration Spring : `ProduitServiceAsyncIT`

Ce test vérifie le vrai chemin d'exécution : le proxy `@Async`, l'exécuteur à threads virtuels, et une exception levée dans le service qui doit traverser le proxy. Il n'utilise pas `@SpringBootTest`, pour rester indépendant de la version de Spring Boot. Il nécessite `spring-test` et Mockito, tous deux fournis par `spring-boot-starter-test`.

```java
package com.exemple.produits;

import com.exemple.produits.dto.AnomalieSource;
import com.exemple.produits.dto.RechercheProduitsResponse;
import com.exemple.produits.dto.SourceProduit;
import com.exemple.produits.dto.TypeAnomalie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Test d'intégration avec un vrai contexte Spring : vrai proxy @Async, vrai exécuteur à threads virtuels,
 * services B et C simulés par Mockito avec de vrais appels bloquants (sleep).
 * Indépendant de la version de Spring Boot (pas de @SpringBootTest).
 */
@SpringJUnitConfig(ProduitServiceAsyncIT.Config.class)
class ProduitServiceAsyncIT {

    private static final ListeProduits LISTE_B = new ListeProduits(List.of(new Produit("B1", "Produit B1")));
    private static final ListeProduits LISTE_C = new ListeProduits(List.of(new Produit("C1", "Produit C1")));

    @Configuration
    @EnableAsync
    static class Config {
        @Bean(name = "taskExecutor")
        AsyncTaskExecutor taskExecutor() {
            return new VirtualThreadTaskExecutor("test-async-");
        }

        @Bean
        ProduitBService produitBService() {
            return mock(ProduitBService.class);
        }

        @Bean
        ProduitCService produitCService() {
            return mock(ProduitCService.class);
        }

        @Bean
        AsyncProduitService asyncProduitService(ProduitBService b, ProduitCService c) {
            return new AsyncProduitService(b, c);
        }

        @Bean
        ProduitService produitService(AsyncProduitService async) {
            return new ProduitService(async, Duration.ofMillis(500));
        }
    }

    @Autowired ProduitService service;
    @Autowired ProduitBService produitBService;
    @Autowired ProduitCService produitCService;

    @BeforeEach
    void reinitialiser() {
        reset(produitBService, produitCService);
    }

    @Test
    void legacyTropLent_reponsePartielleEnEnvironLeTimeout() {
        when(produitBService.listerProduits(any())).thenReturn(LISTE_B);
        when(produitCService.listerProduits(any())).thenAnswer(inv -> { Thread.sleep(3_000); return LISTE_C; });

        long debut = System.nanoTime();
        RechercheProduitsResponse r = service.listerProduitsAvecCriteres(new CriteresFiltres("x"));
        long dureeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - debut);

        assertFalse(r.complet());
        assertEquals(LISTE_B.produits(), r.produits());
        assertEquals(List.of(new AnomalieSource(SourceProduit.C, TypeAnomalie.TIMEOUT)), r.anomalies());
        assertTrue(dureeMs < 1_000, "durée mesurée : " + dureeMs + " ms");
    }

    @Test
    void exceptionLeveeDansB_traverseLeProxyAsync_etEstClasseeErreur() {
        when(produitBService.listerProduits(any())).thenThrow(new IllegalStateException("HTTP 500 sur B"));
        when(produitCService.listerProduits(any())).thenReturn(LISTE_C);

        RechercheProduitsResponse r = service.listerProduitsAvecCriteres(new CriteresFiltres("x"));

        assertEquals(LISTE_C.produits(), r.produits());
        assertEquals(List.of(new AnomalieSource(SourceProduit.B, TypeAnomalie.ERREUR)), r.anomalies());
    }
}
```

### 6.3 Ce qui a été vérifié

L'environnement de vérification n'avait pas accès à Maven Central. Le code a donc été compilé et exécuté avec le JDK 21 seul, en remplaçant les quelques annotations Spring, SLF4J et JUnit par des équivalents minimaux.

| Vérification | Résultat |
|---|---|
| Compilation du code de production (§ 5) | OK |
| `ProduitServiceTest` exécuté tel quel | **9 / 9 OK** |
| Reproduction du chemin `@Async` de Spring 6.1 (tâche soumise sur un exécuteur à threads virtuels, exception enveloppée dans une `CompletionException`), avec de vrais appels bloquants | **4 / 4 OK** |
| `ProduitServiceAsyncIT` (vrai contexte Spring) | **Non exécuté ici** : à lancer dans votre projet |

Mesures relevées pendant la reproduction `@Async` (timeout de 500 ms) :

- **C bloqué 3 s** : réponse partielle obtenue en **505 ms**.
- **B et C à 300 ms chacun** : réponse complète en **302 ms**, ce qui confirme que les deux appels s'exécutent bien en parallèle.
- **B qui lève une exception** : C est bien renvoyé, avec l'anomalie `{B, ERREUR}`.
- **B en erreur et C trop lent** : `SourcesIndisponiblesException` levée, avec les deux anomalies.

---

## 7. Comment vérifier rapidement dans votre projet

1. Ajoutez les classes du § 5 et adaptez l'accesseur `produits()` à votre `ListeProduits`.
2. Lancez `ProduitServiceTest`, puis `ProduitServiceAsyncIT`.
3. Testez en local, avec `produits.recherche.timeout=1s` et un C lent (bouchon, ou point d'arrêt dans `ProduitCService`) :
   - `GET /ListerProducts` doit répondre en environ 1 s avec `complet: false` ;
   - le log doit afficher `Source C en timeout après … ms`.
4. Coupez B et C : la réponse doit être un 503.

---

## 8. Limites connues et suites

- **L'appel en timeout continue de tourner.** `orTimeout` arrête l'attente, pas l'appel HTTP ou SOAP en cours. C'est l'objet de l'étape suivante : configurer les read et connect timeouts du `RestTemplate` et du `WebServiceTemplate`, légèrement en dessous de `produits.recherche.timeout`. Le code est déjà prêt : la `SocketTimeoutException` qui en résultera sera classée `TIMEOUT` (voir le test `timeoutReseauDuClient_estClasseTimeout`).
- **`RequestAttributes`.** Mis de côté pour l'instant. À reprendre avant la production : une tâche qui survit à la requête HTTP (cas du timeout) peut lire un `HttpServletRequest` déjà recyclé par Tomcat.
- **Débranchement du SOAP.** Le jour venu, il faudra retirer l'appel à C et l'entrée `C` de `SourceProduit`. Si plusieurs sources doivent cohabiter durablement, `ProduitService` peut évoluer vers une liste de sources injectées ; la logique d'agrégation fonctionne déjà pour N résultats.
- **Timeout par source.** Avec C débranché, une valeur calée sur le legacy deviendra trop longue pour B seul. Il suffira de baisser `produits.recherche.timeout`, ou d'introduire une surcharge par source.
