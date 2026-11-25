#!/usr/bin/env python3

from pathlib import Path
import unittest

from profile_resolver import (
    InheritanceCycleError,
    MissingParentError,
    load_and_resolve,
)

HERE = Path(__file__).resolve().parent
FIXTURES = HERE / "profile_fixtures"


class ProfileResolverTests(unittest.TestCase):
    def test_nested_and_multi_parent_precedence(self):
        globals_, sections = load_and_resolve(FIXTURES / "inheritance.ini")
        self.assertEqual(globals_["vendor:name"], "Alloy G4 fixture")

        child = sections["printer:Child"]
        self.assertEqual(child["bed_size"], "180x180")
        # Middle inherits Base, then SecondParent overrides Middle, then Child wins.
        self.assertEqual(child["max_speed"], "400")
        self.assertEqual(child["middle_only"], "yes")
        self.assertEqual(child["second_only"], "yes")
        self.assertEqual(child["child_only"], "yes")
        self.assertEqual(child["shared"], "child")
        self.assertNotIn("inherits", child)

    def test_missing_parent_is_explicit_error(self):
        with self.assertRaises(MissingParentError):
            load_and_resolve(FIXTURES / "missing_parent.ini")

    def test_cycle_is_explicit_error(self):
        with self.assertRaises(InheritanceCycleError):
            load_and_resolve(FIXTURES / "cycle.ini")


if __name__ == "__main__":
    unittest.main()
