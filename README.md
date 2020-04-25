# Site Link Validator

Validates URLs in locally stored web pages to reduce broken links and links that point to a redirecting URL. For local pages the HTML anchors are validated.

The tool generates a report identifying files and links that should be fixed.

The configuration file identifies a page root and allows to disable checks for certain URLs and files.

See [application.conf](core/src/main/resources/application.conf) for examples.

This software is open source and released under [Apache License V2](LICENSE).
