# Html2PDF Webservice

This is a very simple webservice, that uses the amazing [OpenHTML2PDF](https://github.com/openhtmltopdf/openhtmltopdf) to convert HTML to PDF.

Currently there are two client implementations available:
- [Extension:PDFCreator](https://www.mediawiki.org/wiki/Extension:PDFCreator).
- [Extension:BlueSpiceUEModulePDF](https://www.mediawiki.org/wiki/Extension:BlueSpiceUEModulePDF)

## JAR file build
To build the JAR file, run:
```bash
mvn clean package
```

### Identifying the running build

Maven generates build metadata during `generate-resources`. Each build has a
default timestamp-based ID and a build time. To assign an explicit diagnostic ID:

```bash
mvn -Djava.version=21 -Dhtml2pdf.build-id=pdf-debug-20261021-1 clean package
```

Use the Java version supported by your local environment and dependencies, or
omit that override when using the project's configured JDK. Use a new build ID
for each diagnostic build.

After replacing the JAR, restart the service. Recreate the container if needed
to refresh a single-file bind mount after the host JAR was replaced. A running
JVM does not automatically load code from a newly compiled JAR.

Query the service directly, for example:

```bash
curl http://localhost:8080/Html2PDF/v1/
```

The JSON response includes `version`, `buildId`, and `buildTime`. Render responses
also carry `X-Html2PDF-Version`, `X-Html2PDF-Build-Id`, and
`X-Html2PDF-Build-Time` headers, including handled renderer failures. These are
headers from the Java service; a wiki proxy/export endpoint may not forward them.

Every generated PDF embeds the same identity in its `Producer` metadata, without
adding visible text or changing page layout. Open the PDF's document properties,
or inspect a downloaded file with:

```bash
pdfinfo exported.pdf
```

Look for a producer such as
`Html2PDF 2.2.3-SNAPSHOT; build=pdf-debug-20261021-1; built=...`.
Matching this with the root endpoint proves which build generated that PDF.
The render-start log entry includes the marker too. An unbuilt IDE launch without
generated Maven metadata reports `development`/`unversioned` instead.

## Docker image
Build the docker image with:
```bash
docker build -t webservice-htmlpdf .
```

Run the container with:
```
docker run -p 8080:8080 webservice-htmlpdf
```
## Included Fonts and Licenses

This project includes the following open source fonts as replacements for the PDF base 14 fonts and
generic fallbacks:

| Font Family         | Font Files (TTF)                        | License                                    |
|---------------------|------------------------------------------|---------------------------------------------|
| Nimbus Roman        | NimbusRoman-Regular, -Bold, -Italic,     | GPL or AFPL (URW++)                        |
|                     | -BoldItalic                              |                                             |
| Nimbus Sans         | NimbusSans-Regular, -Bold, -Italic,      | GPL or AFPL (URW++)                        |
|                     | -BoldItalic                              |                                             |
| Nimbus Mono PS      | NimbusMonoPS-Regular, -Bold, -Italic,    | GPL or AFPL (URW++)                        |
|                     | -BoldItalic                              |                                             |
| Standard Symbols PS | StandardSymbolsPS                        | GPL or AFPL (URW++)                        |
| D050000L            | D050000L                                 | LaTeX Project Public License (LPPL)         |
| Noto                | noto/*                                   | SIL Open Font                              |

Font sources: [URW++ Core 35 Fonts](https://github.com/ArtifexSoftware/urw-base35-fonts), [D050000L](https://ctan.org/pkg/d050000l), [Noto](https://notofonts.github.io/)

Please refer to the respective repositories for full license texts.

## Manual testing
To test the service manually, run:
```bash
cd manual-testing
composer update
php test.php data/doc1/
```

### Isolating textbox accessibility failures

Compiling or redeploying the service does not by itself fix the textbox
accessibility exception. The response-handling change only reports failures as
HTTP 500 instead of an empty successful response.

Two small fixtures compare the CSS-table textbox layout found in the captured
Company history export with a block-layout alternative. They require no external
stylesheets or images and do not disable PDF/UA accessibility.

From `manual-testing`, run against the same service version that failed:

```bash
php test.php data/textbox-table http://localhost:8080/Html2PDF/v1
php test.php data/textbox-block http://localhost:8080/Html2PDF/v1
```

Replace the service URL as needed. Compare the PDF responses and service logs.
If the table fixture reproduces the accessibility exception and the block
fixture succeeds, the textbox layout is isolated as a trigger. This is a
diagnostic comparison, not a confirmed fix for every table-tagging failure.

Alternatively, run the fixtures directly through the controller with Java tests
from the service project directory. No running service or PHP client is needed:

```bash
mvn -Dtest=Html2PdfApplicationTests -Dhtml2pdf.textbox-diagnostic=true test
```

Add the same `-Djava.version=...` override used for a successful local build if
needed. The diagnostic is skipped during ordinary builds. A failure names the
fixture and logs the underlying renderer exception; both cases keep accessibility
enabled. If both fixtures pass, the minimal textbox hypothesis is not sufficient
and the complete document/pagination needs further investigation.

To compare a captured document unchanged with a textbox block-layout variant:

```bash
mvn -Djava.version=21 -Dtest=Html2PdfApplicationTests \
	-Dhtml2pdf.document-diagnostic=../PDFCreator/6b9bad19947604d14759d75c6c24fef3.html test
```

Adjust the Java version and captured file path as needed. The diagnostic does
not modify the source HTML. It copies sibling `stylesheets`, `images`, and
`attachments` directories when available, and reports missing asset directories.
A passing test without the original assets does not establish that the complete
document will render successfully. Test cases are named `captured-original` and
`captured-block-textbox` in controller log entries.

## Liveness probing and version check
To check if the service is responsive one can execute a HTTP request against the `servlet.context-path` (default `/Html2PDF/v1/`)

Example:
```bash
curl http://localhost:8080/Html2PDF/v1/
```
will return somthing like:
```
{"msg":"Service is running","success":true,"version":"2.2.3"}
```

## Fonts and Multi-Script Support

Due to the focus on PDF/A compliance, every font in use must be embedded in the
PDF. We ensure this with three measures:

1. set a `font-family` on the `html` element with a list of fallback fonts
	(Nimbus Roman, Noto fonts) for many commonly used scripts

2. replace all occurences of `sans-serif`, `serif` and `monospace` in
	`font-family` CSS properties with `...-fallback`, and load the appropriate
	fonts with those names

3. append fallback fonts to all occurences of `font-family` in CSS and `<style>`
	elements

This enables us to provide support for a large range of characters. You might
still run into issues for one of the following reasons:

1. use a font that was not uploaded with the HTML file (or provided by the service)

2. set `font-family` to a generic name (`sans-serif`, `serif`, `monospace`) in
	other content, e.g., SVG files

3. use very recent Unicode code points, e.g., the newest emojis, that are not
	yet embedded in the font files

4. use scripts that are not part of our fallback stack, e.g., ancient scripts

To mitigate these problems you will need to provide your own fonts and carefully
craft your `font-family` CSS declarations, so that each glyph is found reliably.

## "bshtml2pdf" compatibility
The old "bshtml2pdf" service ran servlets on a Tomcat server and hat `/BShtml2PDF` as the base URL. This service runs standalone and therefore lacks the `/BShtml2PDF` base URL. The client implementation must be adjusted accordingly.

If that can not be done easily, one can start the service with the `server.servlet.context-path` parameter:

```bash
java -jar \
	-Dserver.servlet.context-path=/BShtml2PDF \
	htmlpdf-1.0.0-SNAPSHOT.jar
```

or using the Docker image:

```bash
docker run -p 8080:8080 -e APP_PATH=/BShtml2PDF webservice-htmlpdf
```

## TODO
* Implement proper support for custom fonts (e.g. from inline CSS)
