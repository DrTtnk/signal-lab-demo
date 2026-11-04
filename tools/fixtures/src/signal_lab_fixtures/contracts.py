import json
import math
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker
from jsonschema import validate as validate_schema
from pydantic import JsonValue, TypeAdapter

ROOT = Path(__file__).resolve().parents[4]
CONTRACT_ROOT = ROOT / "contracts" / "v1"
JSON_OBJECT = TypeAdapter(dict[str, JsonValue])


def reject_constant(value: str) -> object:
    raise ValueError(f"Non-finite JSON number: {value}")


def read_json(path: Path) -> dict[str, JsonValue]:
    value: object = json.loads(path.read_text(), parse_constant=reject_constant)
    json.dumps(value, allow_nan=False)
    return JSON_OBJECT.validate_python(value, strict=True)


def validate(definition: str, value: object) -> None:
    json.dumps(value, allow_nan=False)
    schema = read_json(CONTRACT_ROOT / "schema.json")
    definitions = schema["$defs"]
    if not isinstance(definitions, dict) or definition not in definitions:
        raise ValueError(f"Unknown contract definition: {definition}")
    selected = {**schema, "$ref": f"#/$defs/{definition}"}
    validate_schema(value, selected, cls=Draft202012Validator, format_checker=FormatChecker())


def validate_result(value: object) -> None:
    validate("AnalysisResult", value)
    payload = JSON_OBJECT.validate_python(value, strict=True)
    profile = read_json(CONTRACT_ROOT / "profile.json")
    integer = TypeAdapter(int)
    number = TypeAdapter(float)
    frames = integer.validate_python(payload["processedFrames"])
    windows = integer.validate_python(payload["processedWindows"])
    window_size = integer.validate_python(profile["windowFrames"])
    sample_rate = integer.validate_python(profile["sampleRateHz"])
    if frames != windows * window_size:
        raise ValueError("Frame and window counts disagree")
    channels = TypeAdapter(list[dict[str, JsonValue]]).validate_python(payload["channels"])
    identifiers = [channel["channelIndex"] for channel in channels]
    if len({str(identifier) for identifier in identifiers}) != len(identifiers):
        raise ValueError("Duplicate result channels")
    bands = TypeAdapter(list[list[int]]).validate_python(profile["bands"])
    policy = read_json(CONTRACT_ROOT / "tolerances.json")
    power_tolerance = JSON_OBJECT.validate_python(policy["bandPowers"])
    rtol = number.validate_python(power_tolerance["rtol"])
    atol = number.validate_python(power_tolerance["atol"])
    for channel in channels:
        psd = TypeAdapter(list[float]).validate_python(channel["meanPsd"])
        powers = TypeAdapter(list[float]).validate_python(channel["bandPowers"])
        peak = JSON_OBJECT.validate_python(channel["peak"])
        if max(psd) == 0:
            if peak["kind"] != "NoPeak":
                raise ValueError("A zero spectrum has no peak")
        else:
            index = psd.index(max(psd))
            if (
                peak["kind"] != "Peak"
                or peak["bin"] != index
                or peak["frequencyHz"] != index * sample_rate / window_size
            ):
                raise ValueError("Peak does not match the emitted spectrum")
        for index, (start, end) in enumerate(bands):
            expected = math.fsum(psd[start:end]) * sample_rate / window_size
            if not math.isclose(powers[index], expected, rel_tol=rtol, abs_tol=atol):
                raise ValueError("Band power does not match the emitted spectrum")


def validate_engine_output(control: object, output: object) -> None:
    validate("EngineInput", control)
    validate("EngineOutput", output)
    request = JSON_OBJECT.validate_python(control, strict=True)
    response = JSON_OBJECT.validate_python(output, strict=True)
    recording = JSON_OBJECT.validate_python(request["recording"], strict=True)
    if response["itemId"] != request["itemId"] or response["recordingId"] != recording["recordingId"]:
        raise ValueError("Engine response identity does not match its input")
    if response["state"] == "Failed":
        return
    for name in ("profileId", "workers"):
        if response[name] != request[name]:
            raise ValueError(f"Engine response {name} does not match its input")
    if response["recordingSha256"] != recording["sha256"]:
        raise ValueError("Engine response digest does not match its input")
    if response["recordingProvenance"] != recording["provenance"]:
        raise ValueError("Engine response provenance does not match its input")
    validate_result(response["result"])
    result = JSON_OBJECT.validate_python(response["result"], strict=True)
    integer = TypeAdapter(int)
    frames = integer.validate_python(recording["frameCount"])
    profile = read_json(CONTRACT_ROOT / "profile.json")
    window_size = integer.validate_python(profile["windowFrames"])
    if (
        result["processedFrames"] != frames - frames % window_size
        or result["ignoredTailFrames"] != frames % window_size
    ):
        raise ValueError("Engine frame coverage does not match its input")
    channels = TypeAdapter(list[dict[str, JsonValue]]).validate_python(result["channels"])
    selected = TypeAdapter(list[int]).validate_python(request["channels"])
    available = integer.validate_python(recording["channelCount"])
    if any(channel >= available for channel in selected):
        raise ValueError("Engine success names an unavailable channel")
    if [channel["channelIndex"] for channel in channels] != selected:
        raise ValueError("Engine channel order does not match its input")
