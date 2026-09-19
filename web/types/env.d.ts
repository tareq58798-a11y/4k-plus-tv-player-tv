/** Vite injects import.meta.env; the dev-only hook in main.ts reads DEV from it. */
interface ImportMetaEnv {
  readonly DEV: boolean;
  readonly PROD: boolean;
}
interface ImportMeta {
  readonly env: ImportMetaEnv;
}
