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

### Large document
To test with a large document (~3000 pages, ~800 MB of embedded attachments), generate the
test data first (it is not checked in) and then run the test as usual:
```bash
cd manual-testing
php generate-large-doc.php            # [targetFolder] [pageCount] [attachmentCount] [attachmentSizeMB]
php test.php data/large-doc/
```
Uploads are split into several requests to stay within the service's multipart limits.
Make sure the service has enough heap and disk space for the rendering.

## Validating PDFs with veraPDF
The service produces PDF/A-3u documents with PDF/UA-1 (accessibility) tagging.
[veraPDF](https://verapdf.org) is the reference validator for both standards. It runs in Docker,
so nothing needs to be installed besides Docker itself.

### Using the script
`manual-testing/validate-pdf.sh` checks each given PDF against PDF/UA-1 and PDF/A-3u:
```bash
cd manual-testing
php test.php data/a11y-attachments/        # render a sample document (service must be running)
./validate-pdf.sh data/a11y-attachments/a11y-attachments.pdf data/doc1/doc1.pdf
```
Output for a valid file:
```
PASS /data/a11y-attachments.pdf ua1
PASS /data/a11y-attachments.pdf 3u
```
For an invalid file, the failed rules are listed below the `FAIL` line, e.g. `FAIL 7.18.1-1`.
The script exits with `1` if any check fails, so it can be used in scripts and CI.
It uses `verapdf/cli:latest`; set `VERAPDF_IMAGE` to use a specific version:
```bash
VERAPDF_IMAGE=verapdf/cli:v1.30.4 ./validate-pdf.sh some.pdf
```

### Using veraPDF directly
The PDF's directory is mounted into the container as `/data`:
```bash
# PDF/UA-1, with a list of the failed rules
docker run --rm -v "$PWD:/data:ro" verapdf/cli:latest --flavour ua1 --format text --verbose /data/file.pdf

# PDF/A-3u
docker run --rm -v "$PWD:/data:ro" verapdf/cli:latest --flavour 3u --format text --verbose /data/file.pdf

# Full report with the location of every failed check (object numbers, operators, ...)
docker run --rm -v "$PWD:/data:ro" verapdf/cli:latest --flavour ua1 --format json /data/file.pdf > report.json
```
Useful options:
| Option | Meaning |
|---|---|
| `--flavour ua1` / `ua2` | PDF/UA-1 / PDF/UA-2 |
| `--flavour 3u` / `3a` / `3b` | PDF/A-3 conformance levels; the service targets `3u` |
| `--format text\|xml\|json\|html` | Report format (default `xml`) |
| `--verbose` | Lists failed rules in `text` output |
| `--maxfailuresdisplayed N` | Number of failed checks reported per rule (`-1` for all) |

Each failed rule is identified by the clause of the standard and a test number, e.g. `7.18.1-1`
(PDF/UA-1, clause 7.18.1: annotations must be nested in `Annot` structure elements). The rules are
documented in the [veraPDF rule reference](https://docs.verapdf.org/validation/); the `context` in
the JSON/XML report points to the offending PDF object.

### Sample documents
The checked-in documents in `manual-testing/data/` are expected to `PASS` both profiles.
`data/a11y-attachments/` covers embedded file attachments, links, images with alternative text,
data tables and transparency.

The tests can render them without a running service. Pass an output directory and
validate the result:
```bash
./mvnw verify -Dhtml2pdf.pdf-output-dir="$PWD/target/validation-pdfs"
manual-testing/validate-pdf.sh target/validation-pdfs/*.pdf
```

### Continuous integration
The [CI workflow](.github/workflows/ci.yml) runs on every pull request and on pushes to `main`:
1. **Tests**: builds the service with Java 25 and runs all tests. The sample documents are
	rendered and uploaded as the `validation-pdfs` workflow artifact.
2. **veraPDF**: validates those PDFs with `manual-testing/validate-pdf.sh` using a pinned veraPDF
	image (`VERAPDF_IMAGE` in the workflow; bump it deliberately).

If the veraPDF job fails, download the `validation-pdfs` artifact from the workflow run and use
the commands above to investigate.

## Accessibility post-processing
PDFs are rendered as PDF/A-3u with PDF/UA tagging. To make the output pass validation, the service
- embeds an sRGB OutputIntent (required by PDF/A for any RGB color),
- adds an `AFRelationship` and MIME type to every `<a download>` link that does not specify one,
- nests file attachment annotations in `Annot` structure elements instead of `Link` elements
	(see [openhtmltopdf#66](https://github.com/openhtmltopdf/openhtmltopdf/issues/66)) and sets
	their `/Contents`. This runs only for documents that contain attachments.

## Liveness probing and version check
To check if the service is responsive one can execute a HTTP request against the `servlet.context-path` (default `/Html2PDF/v1/`)

Example:
```bash
curl http://localhost:8080/Html2PDF/v1/
```
will return somthing like:
```
{"msg":"Service is running","success":true,"version":"2.2.5"}
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

## SVG Data Images

SVG images may embed other SVGs using `data:` URLs. A custom Batik data protocol
handler preserves the document URL when resolving fragment-only references such
as `<use xlink:href="#box"/>` inside those images. Both percent-encoded and base64
data URLs are supported. Scripts and HTTP/file resource requests from SVG remain
disabled.

When an SVG contains an image reference blocked by that policy, the service logs
the omission and renders the rest of the drawing with that sub-image left blank.
The original SVG is not modified. This also applies to images inside embedded
SVGs; unrelated rendering errors are not suppressed.

Embedded GIF images are decoded from streams using Java ImageIO, since Batik's
default GIF decoder cannot load data URLs. GIF87a and GIF89a are supported,
including transparency; animated GIFs contribute their first frame to the PDF.

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

## Creating Releases

To create a new release, please update the version number in this file and in
`pom.xml`. As soon as this change is merged, build the `.jar` file
from within the `main` branch:

```
docker build -t webservice-pdf-release-builder .
docker run --rm --detach --name webservice-pdf-release webservice-pdf-release-builder
docker cp webservice-pdf-release:/app/html2pdf.jar /tmp/
docker stop webservice-pdf-release
sha256sum /tmp/html2pdf.jar
```

Then append `/tmp/html2pdf.jar` and its hash sum to the new release on Github.
