package fr.exemple.produits;
/** STUB conforme aux constructeurs décrits. */
public class TechnicalException extends RuntimeException {
    private final EnumCodeErreurTechnique code;
    private final String[] arguments;
    public TechnicalException(EnumCodeErreurTechnique code, Throwable t, String... arguments) {
        super(String.format(code.getTemplate(), (Object[]) arguments), t); this.code = code; this.arguments = arguments;
    }
    public TechnicalException(EnumCodeErreurTechnique code, String... arguments) {
        super(String.format(code.getTemplate(), (Object[]) arguments)); this.code = code; this.arguments = arguments;
    }
    public EnumCodeErreurTechnique getCode() { return code; }
}
