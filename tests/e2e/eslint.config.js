import eslint from "@eslint/js";
import globals from "globals";
import playwright from "eslint-plugin-playwright";
import tseslint from "typescript-eslint";

const specs = ["src/**/*.spec.ts"];

export default tseslint.config(
  {
    ignores: [
      "node_modules",
      "test-results",
      "playwright-report",
      "playwright",
    ],
  },
  eslint.configs.recommended,
  ...tseslint.configs.strictTypeChecked.map((config) => ({
    ...config,
    files: ["**/*.ts"],
  })),
  ...tseslint.configs.stylisticTypeChecked.map((config) => ({
    ...config,
    files: ["**/*.ts"],
  })),
  {
    files: ["**/*.ts"],
    languageOptions: {
      parserOptions: { projectService: true },
      globals: { ...globals.node },
    },
    rules: {
      "no-restricted-syntax": [
        "error",
        {
          selector: "CallExpression[callee.property.name='ok']",
          message: "Assert the exact status code with status(), not ok().",
        },
        {
          selector: "CallExpression[callee.name='fetch']",
          message: "Use the typed methods of api-client.ts, never a raw fetch.",
        },
        {
          selector: "Literal[value=/^https?:/]",
          message: "No hardcoded URL. Read it from env.ts.",
        },
      ],
    },
  },
  {
    ...playwright.configs["flat/recommended"],
    files: specs,
  },
  {
    files: specs,
    rules: {
      "playwright/no-focused-test": "error",
      "playwright/no-skipped-test": "error",
      "playwright/no-wait-for-timeout": "error",
      "playwright/no-conditional-in-test": "error",
      "playwright/no-force-option": "error",
      "playwright/no-networkidle": "error",
      "playwright/expect-expect": "error",
      "playwright/missing-playwright-await": "error",
      "no-restricted-syntax": [
        "error",
        {
          selector: "CallExpression[callee.property.name='ok']",
          message: "Assert the exact status code with status(), not ok().",
        },
        {
          selector: "CallExpression[callee.name='fetch']",
          message: "Use the typed methods of api-client.ts, never a raw fetch.",
        },
        {
          selector: "Literal[value=/^https?:/]",
          message: "No hardcoded URL. Read it from env.ts.",
        },
        {
          selector: "CallExpression[callee.property.name='goto']",
          message:
            "Navigate with a helper of helpers.ts, not page.goto in a test.",
        },
        {
          selector:
            "CallExpression[callee.name='test'] > Literal:first-child:not([value=/^[0-9]+[a-z]?[0-9]? — /])",
          message:
            "Name a test '{number} — {what it does}'. The number is the execution order.",
        },
      ],
    },
  },
);
