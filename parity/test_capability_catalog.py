import json
import sys
import tempfile
import unittest
from pathlib import Path


REPOSITORY = Path(__file__).resolve().parents[1]
if str(REPOSITORY) not in sys.path:
    sys.path.insert(0, str(REPOSITORY))

from ci import validate_capability_catalog as validator


class CapabilityCatalogValidationTests(unittest.TestCase):
    def current_catalog(self):
        return json.loads((REPOSITORY / "app/src/main/assets/capabilities/bambu-initial-v1.json")
                          .read_text(encoding="utf-8"))

    def validate(self, value):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "catalog.json"
            path.write_text(json.dumps(value), encoding="utf-8")
            validator.validate_catalog(path)

    def assert_rejected(self, value, expected):
        with self.assertRaisesRegex(ValueError, expected):
            self.validate(value)

    def test_current_catalog_is_a_valid_fail_closed_initial_cohort(self):
        self.validate(self.current_catalog())

    def test_rejects_non_official_capability_source(self):
        value = self.current_catalog()
        value["printers"][0]["source_url"] = "https://example.test/a1-mini"
        self.assert_rejected(value, "direct HTTPS bambulab.com URL")

    def test_rejects_accidental_direct_send_promotion(self):
        value = self.current_catalog()
        value["printers"][0]["alloy_direct_send"] = "Qualified"
        self.assert_rejected(value, "must stay not qualified")

    def test_rejects_missing_a1_mini_spool_form(self):
        value = self.current_catalog()
        value["printers"][0]["spool_forms"] = value["printers"][0]["spool_forms"][:-1]
        self.assert_rejected(value, "must disclose complete, refill, third-party and regular-AMS spool forms")

    def test_rejects_missing_individual_a1_mini_material_class(self):
        value = self.current_catalog()
        value["printers"][0]["materials"] = [
            material for material in value["printers"][0]["materials"]
            if material["id"] != "petg-cf"
        ]
        self.assert_rejected(value, "must disclose each ideal and not-recommended material class separately")

    def test_rejects_unofficial_material_or_spool_sources(self):
        material_source = self.current_catalog()
        material_source["printers"][0]["materials"][0]["source_url"] = "https://example.test/pla"
        self.assert_rejected(material_source, "material.source_url must be a direct HTTPS bambulab.com URL")
        spool_source = self.current_catalog()
        spool_source["printers"][0]["spool_forms"][0]["source_url"] = "https://example.test/spool"
        self.assert_rejected(spool_source, "spool_form.source_url must be a direct HTTPS bambulab.com URL")

    def test_rejects_material_direct_send_promotion(self):
        value = self.current_catalog()
        value["printers"][0]["materials"][0]["alloy_direct_send"] = "Qualified"
        self.assert_rejected(value, "material must stay unavailable for Alloy direct send")


if __name__ == "__main__":
    unittest.main()
