package fr.exemple.produits;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
/** Reproduction de la classe existante (inchangée). */
@Service
public class AsyncProduitService {
    private final ProduitBService produitBService;
    private final ProduitAService produitAService;
    public AsyncProduitService(ProduitBService produitBService, ProduitAService produitAService) {
        this.produitBService = produitBService; this.produitAService = produitAService;
    }
    @Async("taskExecutor")
    public CompletableFuture<ListeProduits> listerProduitsB(CriteresFiltres criteres) {
        return CompletableFuture.completedFuture(produitBService.listerProduits(criteres));
    }
    @Async("taskExecutor")
    public CompletableFuture<ListeProduits> listerProduitsA(CriteresFiltres criteres) {
        return CompletableFuture.completedFuture(produitAService.listerProduits(criteres));
    }
}
