function required(name: string): string {
  const value = process.env[name];
  if (value === undefined) {
    throw new Error(`${name} is required`);
  }
  return value;
}

export const env = {
  get webBaseUrl(): string {
    return required("WEB_BASE_URL");
  },
  get compose(): { file: string; envFile: string } {
    return {
      file: required("SIGNAL_LAB_COMPOSE_FILE"),
      envFile: required("SIGNAL_LAB_ENV_FILE"),
    };
  },
};
