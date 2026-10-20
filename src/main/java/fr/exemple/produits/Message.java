package fr.exemple.produits;
import java.util.List;
import lombok.Getter;
/** STUB */
@Getter
public class Message {
    private final String code;
    private final List<String> arguments;
    private Message(String code, List<String> arguments) { this.code = code; this.arguments = arguments; }
    public static Message fromEnum(EnumCodeErreurFonctionnel code, String... arguments) { return new Message(code.getCode(), List.of(arguments)); }
    public static Message fromEnum(EnumCodeErreurTechnique code, String... arguments) { return new Message(code.getCode(), List.of(arguments)); }
}
