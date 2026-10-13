package io.github.jiyuuchc.biopb.fiji;

import java.util.function.ToIntFunction;

/** How many lines a text takes when wrapped at word boundaries. */
final class TextWrap {
    private TextWrap() { }

    /**
     * @param text the text
     * @param width the line width, in the units {@code measure} returns
     * @param measure the width of a string
     * @return at least 1; a word wider than the line takes as many lines as it needs
     */
    static int lines(String text, int width, ToIntFunction<String> measure) {
        if (text == null || text.trim().isEmpty() || width <= 0) {
            return 1;
        }
        int lines = 1;
        String line = "";
        for (String word : text.trim().split("\\s+")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (measure.applyAsInt(next) <= width) {
                line = next;
                continue;
            }
            if (!line.isEmpty()) {
                lines++;
            }
            int w = measure.applyAsInt(word);
            lines += Math.max(0, (w - 1) / width);
            line = word;
        }
        return lines;
    }
}
