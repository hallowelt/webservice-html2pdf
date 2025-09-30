package com.hallowelt.mediawiki.services.html2pdf;

import com.openhtmltopdf.extend.FSSupplier;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import java.io.InputStream;
import java.util.Map;

public class BaseFontMapping {

    // Map of all acceptable family names/aliases to the TTF path to load
    private static final Map<String, String> FONT_ALIASES_TO_TTF;

    static {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();

        // Times (URW Nimbus Roman)
        register(m, "/fonts/NimbusRoman-Regular.ttf",
                "Times",
                "Times-Roman",
                "Times Roman",
                "Times New Roman",
                "TimesNewRoman",
                "Nimbus Roman",
                "NimbusRoman",
                "Nimbus Roman No9 L");
        register(m, "/fonts/NimbusRoman-Bold.ttf",
                "Times-Bold",
                "Times Roman Bold",
                "Times-BoldMT",
                "Times New Roman Bold",
                "Nimbus Roman Bold",
                "NimbusRoman-Bold");
        register(m, "/fonts/NimbusRoman-Italic.ttf",
                "Times-Italic",
                "Times-Oblique",
                "Times Roman Italic",
                "Times New Roman Italic",
                "Nimbus Roman Italic",
                "NimbusRoman-Italic");
        register(m, "/fonts/NimbusRoman-BoldItalic.ttf",
                "Times-BoldItalic",
                "Times-BoldOblique",
                "Times Roman Bold Italic",
                "Times New Roman Bold Italic",
                "Nimbus Roman Bold Italic",
                "NimbusRoman-BoldItalic");

        // Helvetica (URW Nimbus Sans)
        register(m, "/fonts/NimbusSans-Regular.ttf",
                "Helvetica",
                "Helvetica Neue",
                "HelveticaNeue",
                "Arial",
                "ArialMT",
                "Nimbus Sans",
                "NimbusSans",
                "Liberation Sans");
        register(m, "/fonts/NimbusSans-Bold.ttf",
                "Helvetica-Bold",
                "Helvetica Neue Bold",
                "Arial Bold",
                "Arial-BoldMT",
                "Nimbus Sans Bold",
                "NimbusSans-Bold",
                "Liberation Sans Bold");
        register(m, "/fonts/NimbusSans-Italic.ttf",
                "Helvetica-Oblique",
                "Helvetica-Italic",
                "Helvetica Neue Italic",
                "Arial Italic",
                "Nimbus Sans Italic",
                "NimbusSans-Italic",
                "Liberation Sans Italic");
        register(m, "/fonts/NimbusSans-BoldItalic.ttf",
                "Helvetica-BoldOblique",
                "Helvetica-BoldItalic",
                "Helvetica Neue Bold Italic",
                "Arial Bold Italic",
                "Nimbus Sans Bold Italic",
                "NimbusSans-BoldItalic",
                "Liberation Sans Bold Italic");

        // Courier (URW Nimbus Mono PS)
        register(m, "/fonts/NimbusMonoPS-Regular.ttf",
                "Courier",
                "Courier New",
                "CourierNew",
                "Nimbus Mono",
                "NimbusMonoPS",
                "Liberation Mono");
        register(m, "/fonts/NimbusMonoPS-Bold.ttf",
                "Courier-Bold",
                "Courier New Bold",
                "Nimbus Mono Bold",
                "NimbusMonoPS-Bold",
                "Liberation Mono Bold");
        register(m, "/fonts/NimbusMonoPS-Italic.ttf",
                "Courier-Oblique",
                "Courier-Italic",
                "Courier New Italic",
                "Nimbus Mono Italic",
                "NimbusMonoPS-Italic",
                "Liberation Mono Italic");
        register(m, "/fonts/NimbusMonoPS-BoldItalic.ttf",
                "Courier-BoldOblique",
                "Courier-BoldItalic",
                "Courier New Bold Italic",
                "Nimbus Mono Bold Italic",
                "NimbusMonoPS-BoldItalic",
                "Liberation Mono Bold Italic");

        // Symbol & Dingbats
        register(m, "/fonts/StandardSymbolsPS.ttf",
                "Symbol",
                "Standard Symbols PS",
                "SymbolMT",
                "Symbols");
        register(m, "/fonts/D050000L.ttf",
                "ZapfDingbats",
                "Zapf Dingbats",
                "Dingbats",
                "ITC Zapf Dingbats");

        FONT_ALIASES_TO_TTF = java.util.Collections.unmodifiableMap(m);
    }

    private static void register(Map<String, String> m, String path, String... names) {
        for (String n : names) {
            m.put(n, path);
        }
    }

    /**
     * Registers the Base 14 fonts (plus common aliases) for OpenHTMLtoPDF using URW TTF fonts.
     */
    public static void registerFonts(PdfRendererBuilder builder) {
        FONT_ALIASES_TO_TTF.forEach((familyName, fontPath) -> {
            FSSupplier<InputStream> supplier = () ->
                BaseFontMapping.class.getResourceAsStream(fontPath);
            builder.useFont(supplier, familyName);
        });
    }
}