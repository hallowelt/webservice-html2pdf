# Html2PDF Webservice

This is a very simple webservice, that uses the amazing [OpenHTML2PDF](https://github.com/danfickle/openhtmltopdf) to convert HTML to PDF.

Currently there is only one client implementation available: [Extension:BlueSpiceUEModulePDF](https://www.mediawiki.org/wiki/Extension:BlueSpiceUEModulePDF).

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

## Manual testing
To test the service manually, run:
```bash
cd manual-testing
composer update
php test.php data/doc1/
```

## "bshtml2pdf" compatibility
The old "bshtml2pdf" service ran servlets on a Tomcat server and hat `/BShtml2PDF` as the base URL. This service runs standalone and therefore lacks the `/BShtml2PDF` base URL. The client implementation must be adjusted accordingly.

If that can not be done easily, one cas start the service with the `server.servlet.context-path` parameter:

```bash
java -jar \
	-Dserver.servlet.context-path=/BShtml2PDF \
	htmlpdf-1.0.0-SNAPSHOT.jar
```

or using the Docker image:

```bash
docker run -p 8080:8080 -e APP_PATH=/BShtml2PDF webservice-htmlpdf
```