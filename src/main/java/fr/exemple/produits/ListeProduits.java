package fr.exemple.produits;
import java.util.List;
import lombok.AllArgsConstructor; import lombok.Builder; import lombok.Data; import lombok.NoArgsConstructor;
/** STUB conforme à la description : Lombok NoArg/AllArg, @Builder, @Data. */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ListeProduits {
    private List<Produit> produits;
    private int total;
    private List<Message> messages;
}
