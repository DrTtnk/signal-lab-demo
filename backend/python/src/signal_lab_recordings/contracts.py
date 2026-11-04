import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from jsonschema import validate as validate_schema


def validate(contracts_dir: Path, definition: str, value: object) -> None:
    schema = json.loads((contracts_dir / "schema.json").read_text())
    validate_schema(
        value,
        {**schema, "$ref": f"#/$defs/{definition}"},
        cls=Draft202012Validator,
        format_checker=FormatChecker(),
    )
