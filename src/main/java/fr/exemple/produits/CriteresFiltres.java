package fr.exemple.produits;

import lombok.Data;

/** STUB : seul le champ bSeulement est utile ici. */
@Data
public class CriteresFiltres {
    /** true : seule la source B est interrogée. null ou false : B et le legacy. */
    private Boolean bSeulement;
}
