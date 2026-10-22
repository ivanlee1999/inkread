"""Accept only the intended released-reader default-selection regression.

This validates a diagnostic run, not the corrected candidate's passing tests.
All normal native matrix runs must still have zero failures/errors/skips.
"""
import json
import pathlib
import sys
import xml.etree.ElementTree as ET


def verify(root: pathlib.Path, exit_code: int) -> dict:
    reports = list(root.rglob("TEST-*.xml"))
    assert reports, "Missing baseline native results"
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    cases = []
    for path in reports:
        suite = ET.parse(path).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, 0))
        cases.extend(suite.findall("testcase"))
    assert exit_code != 0, "Released reader unexpectedly passed the default-selection regression"
    assert totals == dict(tests=1, failures=1, errors=0, skipped=0), totals
    assert len(cases) == 1, "Expected exactly one baseline regression test"
    case = cases[0]
    assert case.get("classname") == "me.ash.reader.ui.page.home.reading.EInkReaderAnnotationUiTest"
    assert case.get("name") == "defaultLongPressShowsNativeAnnotationToolbar"
    failure = case.find("failure")
    assert failure is not None
    detail = failure.get("message", "") + "\n" + (failure.text or "")
    assert "DEFAULT_SELECTION_TOOLBAR_MISSING" in detail, detail
    return dict(
        expected_failure=True,
        implementation="Released 0.16.16 EInkPaginatedContent.kt with the candidate regression test",
        diagnostic="DEFAULT_SELECTION_TOOLBAR_MISSING",
        **totals,
    )


if __name__ == "__main__":
    result = verify(pathlib.Path(sys.argv[1]), int(sys.argv[2]))
    print(json.dumps(result, indent=2))
