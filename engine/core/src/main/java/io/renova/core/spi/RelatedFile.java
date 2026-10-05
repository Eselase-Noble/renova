package io.renova.core.spi;

/**
 * A file that an AI edit of another file may need: for example the build file that owns a source
 * file. Editable related files may be changed in the same edit; the others are reference only.
 *
 * @param path project-relative path
 * @param why  short explanation shown to the model, e.g. "build file of module acme-shop"
 */
public record RelatedFile(String path, boolean editable, String why) {
}
