#!/usr/bin/env python3
"""Reject the old missing-budget JAR shape and duplicate shared class definitions."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("verify_artifact", Path(__file__).with_name("verify-artifact.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class VisualOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.entries = list(verifier.SHARED_VISUAL_CLASSES) + ["visual-packets.yml"]

    def test_one_complete_host_contract_is_accepted(self):
        verifier.verify_visual_ownership(self.entries)

    def test_each_missing_contract_or_runtime_class_is_rejected(self):
        for name in verifier.SHARED_VISUAL_CLASSES:
            with self.subTest(name=name), self.assertRaisesRegex(AssertionError, "exactly one host definition"):
                verifier.verify_visual_ownership([entry for entry in self.entries if entry != name])

    def test_duplicate_host_definition_is_rejected(self):
        for name in verifier.SHARED_VISUAL_CLASSES:
            with self.subTest(name=name), self.assertRaisesRegex(AssertionError, "exactly one host definition"):
                verifier.verify_visual_ownership(self.entries + [name])

    def test_another_metrics_runtime_or_exporter_is_rejected(self):
        for name in [
            "ru/arc/metrics/core/CachedGaugeStore.class",
            "io/micrometer/prometheusmetrics/PrometheusMeterRegistry.class",
        ]:
            with self.subTest(name=name), self.assertRaisesRegex(AssertionError, "must.*not another|must not embed another"):
                verifier.verify_visual_ownership(self.entries + [name])

    def test_missing_host_policy_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "canonical visual packet policy"):
            verifier.verify_visual_ownership(list(verifier.SHARED_VISUAL_CLASSES))


if __name__ == "__main__":
    unittest.main()
