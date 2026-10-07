package io.renova.core.licence;

/** A migration was asked for that the installed licence, or the lack of one, does not allow. */
public class LicenceException extends RuntimeException {

    public LicenceException(String message) {
        super(message);
    }
}
