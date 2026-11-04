import eslint from "@eslint/js";
import jsxA11y from "eslint-plugin-jsx-a11y";
import hooks from "eslint-plugin-react-hooks";
import globals from "globals";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist", "node_modules"] },
  eslint.configs.recommended,
  ...tseslint.configs.strictTypeChecked.map((config) => ({
    ...config,
    files: ["**/*.{ts,tsx}"],
  })),
  ...tseslint.configs.stylisticTypeChecked.map((config) => ({
    ...config,
    files: ["**/*.{ts,tsx}"],
  })),
  {
    files: ["**/*.{ts,tsx}"],
    languageOptions: {
      parserOptions: { projectService: true },
      globals: { ...globals.browser, ...globals.node },
    },
    plugins: { "jsx-a11y": jsxA11y, "react-hooks": hooks },
    settings: { react: { version: "detect" } },
    rules: {
      ...jsxA11y.configs.recommended.rules,
      ...hooks.configs.recommended.rules,
    },
  },
);
