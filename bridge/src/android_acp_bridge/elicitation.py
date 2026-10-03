from __future__ import annotations

import json
import math
from typing import Any


def qwen_question_form(params: dict[str, Any]) -> dict[str, Any]:
    questions = params["toolCall"]["_meta"].get("qwenQuestions")
    if not isinstance(questions, list) or not 1 <= len(questions) <= 4:
        raise ValueError("Qwen questions must contain between one and four questions.")
    options = params.get("options")
    if not isinstance(options, list) or not any(
        isinstance(option, dict) and option.get("optionId") == "proceed_once" and option.get("kind") == "allow_once"
        for option in options
    ):
        raise ValueError("Qwen question has no supported submit option.")
    properties = {}
    for index, question in enumerate(questions):
        if not isinstance(question, dict) or not isinstance(question.get("question"), str) or not question["question"].strip():
            raise ValueError("Invalid Qwen question text.")
        choices = question.get("options")
        if not isinstance(choices, list) or not 2 <= len(choices) <= 4 or any(
            not isinstance(choice, dict) or not isinstance(choice.get("label"), str)
            or not isinstance(choice.get("description", ""), str) for choice in choices
        ):
            raise ValueError("Invalid Qwen question choices.")
        description = "\n".join(f'{choice["label"]}: {choice.get("description", "")}' for choice in choices)
        description += "\nEnter an option label or your own answer."
        if question.get("multiSelect") is True:
            description += " Separate multiple choices with commas."
        # Qwen's extension consumes string answers keyed by question index,
        # including free-form Other answers and comma-separated multi-selects.
        properties[str(index)] = {"type": "string", "title": question["question"],
                                  "description": description, "minLength": 1}
    form = {"sessionId": params.get("sessionId"), "toolCallId": params["toolCall"].get("toolCallId"),
            "mode": "form", "message": "Answer Qwen Code's questions",
            "requestedSchema": {"type": "object", "properties": properties, "required": list(properties)}}
    validate_form(form)
    return form


def validate_form(params: dict[str, Any]) -> dict[str, Any]:
    if params.get("mode", "form") != "form":
        raise ValueError("Only form questions are supported. Complete URL authentication on the machine.")
    schema = params.get("requestedSchema")
    if not isinstance(schema, dict) or schema.get("type") != "object":
        raise ValueError("Question must contain an object schema.")
    properties = schema.get("properties")
    if not isinstance(properties, dict) or not 1 <= len(properties) <= 24:
        raise ValueError("Question must have between 1 and 24 fields.")
    if set(schema) - {"type", "properties", "required", "additionalProperties", "title", "description", "_meta"}:
        raise ValueError("Unsupported question schema constraints.")
    if "additionalProperties" in schema and type(schema["additionalProperties"]) is not bool:
        raise ValueError("Additional question field schemas are unsupported.")
    required = schema.get("required", [])
    if not isinstance(required, list) or any(not isinstance(key, str) or key not in properties for key in required):
        raise ValueError("Invalid required question fields.")
    if len(json.dumps(schema)) > 48_000:
        raise ValueError("Question form is too large.")
    for field in properties.values():
        if not isinstance(field, dict) or field.get("type") not in {"string", "boolean", "number", "integer", "array"}:
            raise ValueError("Unsupported question field type.")
        allowed = {"type", "title", "description", "_meta", "default", "enum", "enumNames", "oneOf",
                   "minLength", "maxLength", "minimum", "maximum", "items", "minItems", "maxItems"}
        if set(field) - allowed:
            raise ValueError("Unsupported question field constraint.")
        if field["type"] == "array" and not enum_values(field.get("items", {})):
            raise ValueError("Only enumerated multi-select question arrays are supported.")
        if field["type"] == "array" and set(field["items"]) - {"type", "enum", "enumNames", "oneOf", "anyOf", "title", "description", "_meta"}:
            raise ValueError("Unsupported multi-select item constraint.")
        enum_values(field)
        for key in ("minLength", "maxLength", "minItems", "maxItems"):
            if key in field and (type(field[key]) is not int or not 0 <= field[key] <= 48_000):
                raise ValueError("Invalid question size constraint.")
        for key in ("minimum", "maximum"):
            if key in field and (type(field[key]) not in {int, float} or not math.isfinite(field[key])):
                raise ValueError("Invalid numeric question constraint.")
    return schema


def enum_values(field: dict[str, Any]) -> list[str] | None:
    if not isinstance(field, dict):
        raise ValueError("Invalid question choice schema.")
    values = field.get("enum")
    variants = field.get("oneOf", field.get("anyOf"))
    if variants is not None:
        if not isinstance(variants, list) or any(
            not isinstance(item, dict) or set(item) - {"const", "title", "description", "_meta"} for item in variants
        ):
            raise ValueError("Unsupported question choices.")
        values = [item.get("const") for item in variants]
    if values is not None and (
        not isinstance(values, list) or not 1 <= len(values) <= 64
        or any(not isinstance(value, str) for value in values) or len(set(values)) != len(values)
    ):
        raise ValueError("Invalid question choices.")
    return values


def validate_answers(schema: dict[str, Any], answers: Any) -> dict[str, Any]:
    if not isinstance(answers, dict) or len(json.dumps(answers)) > 48_000:
        raise ValueError("Question answers must be a bounded object.")
    properties = schema["properties"]
    if set(answers) - set(properties) or any(key not in answers for key in schema.get("required", [])):
        raise ValueError("Question answers contain unknown fields or omit a required field.")
    for key, value in answers.items():
        field = properties[key]
        kind = field["type"]
        valid = (
            (kind == "string" and isinstance(value, str))
            or (kind == "boolean" and type(value) is bool)
            or (kind == "integer" and type(value) is int)
            or (kind == "number" and type(value) in {float, int} and math.isfinite(value))
            or (kind == "array" and isinstance(value, list) and all(isinstance(item, str) for item in value))
        )
        if not valid:
            raise ValueError(f"Invalid answer type for {key}.")
        choices = enum_values(field)
        if choices is not None and value not in choices:
            raise ValueError(f"Invalid selected option for {key}.")
        if kind == "array":
            choices = enum_values(field["items"]) or []
            if len(set(value)) != len(value) or any(item not in choices for item in value):
                raise ValueError(f"Invalid multi-select answer for {key}.")
        if kind in {"string", "array"}:
            lo, hi = ("minLength", "maxLength") if kind == "string" else ("minItems", "maxItems")
            if not field.get(lo, 0) <= len(value) <= field.get(hi, 48_000):
                raise ValueError(f"Answer size outside permitted range for {key}.")
        if kind in {"integer", "number"} and not field.get("minimum", -math.inf) <= value <= field.get("maximum", math.inf):
            raise ValueError(f"Answer outside permitted numeric range for {key}.")
    return answers
