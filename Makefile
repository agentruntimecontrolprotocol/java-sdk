.PHONY: docs-api

# Regenerate the per-package Markdown API reference under docs/api/. The site
# at arpc.dev ingests <lang>-sdk/docs/**/*.md at build time, so this is the
# authoritative location for the published Java API reference.
docs-api:
	python3 scripts/gen-api-docs.py
