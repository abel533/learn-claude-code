import * as fs from "fs";
import * as path from "path";
import type {
  AgentVersion,
  VersionDiff,
  DocContent,
  VersionIndex,
} from "../src/types/agent-data";
import { VERSION_META, VERSION_ORDER, LEARNING_PATH } from "../src/lib/constants";

// Resolve paths relative to this script's location (web/scripts/)
const WEB_DIR = path.resolve(__dirname, "..");
const REPO_ROOT = path.resolve(WEB_DIR, "..");
const AGENTS_DIR = path.join(REPO_ROOT, "agents");
const JAVA_SRC_DIR = path.join(REPO_ROOT, "src", "main", "java", "io", "mybatis", "learn");
const DOCS_DIR = path.join(REPO_ROOT, "docs");
const OUT_DIR = path.join(WEB_DIR, "src", "data", "generated");

// Map python filenames to version IDs
// s01_agent_loop.py -> s01
// s02_tools.py -> s02
// s_full.py -> s_full (reference agent, typically skipped)
function filenameToVersionId(filename: string): string | null {
  const base = path.basename(filename, ".py");
  if (base === "s_full") return null;
  if (base === "__init__") return null;

  const match = base.match(/^(s\d+[a-c]?)_/);
  if (!match) return null;
  return match[1];
}

// Extract classes from Python source
function extractClasses(
  lines: string[]
): { name: string; startLine: number; endLine: number }[] {
  const classes: { name: string; startLine: number; endLine: number }[] = [];
  const classPattern = /^class\s+(\w+)/;

  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(classPattern);
    if (m) {
      const name = m[1];
      const startLine = i + 1;
      // Find end of class: next class/function at indent 0, or EOF
      let endLine = lines.length;
      for (let j = i + 1; j < lines.length; j++) {
        if (
          lines[j].match(/^class\s/) ||
          lines[j].match(/^def\s/) ||
          (lines[j].match(/^\S/) && lines[j].trim() !== "" && !lines[j].startsWith("#") && !lines[j].startsWith("@"))
        ) {
          endLine = j;
          break;
        }
      }
      classes.push({ name, startLine, endLine });
    }
  }
  return classes;
}

// Extract top-level functions from Python source
function extractFunctions(
  lines: string[]
): { name: string; signature: string; startLine: number }[] {
  const functions: { name: string; signature: string; startLine: number }[] = [];
  const funcPattern = /^def\s+(\w+)\((.*?)\)/;

  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(funcPattern);
    if (m) {
      functions.push({
        name: m[1],
        signature: `def ${m[1]}(${m[2]})`,
        startLine: i + 1,
      });
    }
  }
  return functions;
}

// Extract tool names from Python source
// Looks for "name": "tool_name" patterns in dict literals
function extractTools(source: string): string[] {
  const toolPattern = /"name"\s*:\s*"(\w+)"/g;
  const tools = new Set<string>();
  let m;
  while ((m = toolPattern.exec(source)) !== null) {
    tools.add(m[1]);
  }
  return Array.from(tools);
}

// Extract classes/interfaces/records/enums from Java source
function extractJavaClasses(
  lines: string[]
): { name: string; startLine: number; endLine: number }[] {
  const classes: { name: string; startLine: number; endLine: number }[] = [];
  const classPattern = /^(?:public\s+)?(?:class|interface|record|enum)\s+(\w+)/;

  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(classPattern);
    if (m) {
      const name = m[1];
      const startLine = i + 1;
      let endLine = lines.length;
      for (let j = i + 1; j < lines.length; j++) {
        if (lines[j].match(/^(?:public\s+)?(?:class|interface|record|enum)\s/)) {
          endLine = j;
          break;
        }
      }
      classes.push({ name, startLine, endLine });
    }
  }
  return classes;
}

// Extract methods from Java source (skipping constructors)
function extractJavaMethods(
  lines: string[]
): { name: string; signature: string; startLine: number }[] {
  const methods: { name: string; signature: string; startLine: number }[] = [];
  const methodPattern =
    /^\s+(?:(?:public|private|protected|static|default|abstract|final|synchronized|native)\s+)*(?:\w+(?:<[^>]*>)?(?:\[\])?)\s+(\w+)\s*\(/;

  const classNames = new Set<string>();
  const classPattern = /^(?:public\s+)?(?:class|interface|record|enum)\s+(\w+)/;
  for (const line of lines) {
    const m = line.match(classPattern);
    if (m) classNames.add(m[1]);
  }

  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(methodPattern);
    if (m) {
      const name = m[1];
      if (classNames.has(name)) continue;
      const sig = lines[i].trim().replace(/\{?\s*$/, "").trim();
      methods.push({ name, signature: sig, startLine: i + 1 });
    }
  }
  return methods;
}

// Extract tool names from Java @Tool annotations
function extractJavaTools(lines: string[]): string[] {
  const tools: string[] = [];
  const toolAnnotation = /^\s*@Tool\b/;
  const methodPattern =
    /^\s+(?:(?:public|private|protected|static|default|abstract|final|synchronized|native)\s+)*(?:\w+(?:<[^>]*>)?(?:\[\])?)\s+(\w+)\s*\(/;

  for (let i = 0; i < lines.length; i++) {
    if (toolAnnotation.test(lines[i])) {
      for (let j = i + 1; j < Math.min(i + 10, lines.length); j++) {
        const m = lines[j].match(methodPattern);
        if (m) {
          tools.push(m[1]);
          break;
        }
      }
    }
  }
  return tools;
}

// Count non-blank, non-comment lines (supports Python # and Java // /* */ comments)
function countLoc(lines: string[]): number {
  let inBlockComment = false;
  let count = 0;
  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed === "") continue;

    if (inBlockComment) {
      if (trimmed.includes("*/")) inBlockComment = false;
      continue;
    }
    if (trimmed.startsWith("/*")) {
      if (!trimmed.includes("*/")) inBlockComment = true;
      continue;
    }

    if (trimmed.startsWith("#") || trimmed.startsWith("//")) continue;

    count++;
  }
  return count;
}

// Detect locale from subdirectory path
// docs/en/s01-the-agent-loop.md -> "en"
// docs/zh/s01-the-agent-loop.md -> "zh"
// docs/ja/s01-the-agent-loop.md -> "ja"
function detectLocale(relPath: string): "en" | "zh" | "ja" {
  if (relPath.startsWith("zh/") || relPath.startsWith("zh\\")) return "zh";
  if (relPath.startsWith("ja/") || relPath.startsWith("ja\\")) return "ja";
  return "en";
}

// Extract version from doc filename (e.g., "s01-the-agent-loop.md" -> "s01")
function extractDocVersion(filename: string): string | null {
  const m = filename.match(/^(s\d+[a-c]?)-/);
  return m ? m[1] : null;
}

// Main extraction
function main() {
  console.log("Extracting content from agents and docs...");
  console.log(`  Repo root: ${REPO_ROOT}`);
  console.log(`  Java src dir: ${JAVA_SRC_DIR}`);
  console.log(`  Agents dir (fallback): ${AGENTS_DIR}`);
  console.log(`  Docs dir: ${DOCS_DIR}`);

  const versions: AgentVersion[] = [];
  const useJava = fs.existsSync(JAVA_SRC_DIR);

  if (useJava) {
    // 1. Read Java sources from s01~s12 + full subdirectories
    const subdirs = fs
      .readdirSync(JAVA_SRC_DIR)
      .filter((d) => /^s\d+$/.test(d) || d === "full")
      .filter((d) => fs.statSync(path.join(JAVA_SRC_DIR, d)).isDirectory());

    console.log(`  Found ${subdirs.length} Java lesson directories`);

    for (const dir of subdirs) {
      const versionId = dir;
      const dirPath = path.join(JAVA_SRC_DIR, dir);
      const javaFiles = fs.readdirSync(dirPath).filter((f) => f.endsWith(".java"));
      if (javaFiles.length === 0) continue;

      // 主类文件排在最前（如 S01AgentLoop.java）
      const mainPrefix = dir === "full" ? "SFull" : "S" + dir.substring(1);
      javaFiles.sort((a, b) => {
        const aMain = a.startsWith(mainPrefix) ? 0 : 1;
        const bMain = b.startsWith(mainPrefix) ? 0 : 1;
        if (aMain !== bMain) return aMain - bMain;
        return a.localeCompare(b);
      });
      const mainFile = javaFiles[0];

      // 拼接目录下所有 Java 文件，文件间用分隔符标记
      const sourceParts: string[] = [];
      for (const jf of javaFiles) {
        const content = fs.readFileSync(path.join(dirPath, jf), "utf-8");
        sourceParts.push(`// === ${jf} ===\n${content}`);
      }
      const source = sourceParts.join("\n\n");
      const lines = source.split("\n");

      const meta = VERSION_META[versionId];
      const classes = extractJavaClasses(lines);
      const methods = extractJavaMethods(lines);
      const tools = extractJavaTools(lines);
      const loc = countLoc(lines);

      versions.push({
        id: versionId,
        filename: mainFile,
        title: meta?.title ?? versionId,
        subtitle: meta?.subtitle ?? "",
        loc,
        tools,
        newTools: [],
        coreAddition: meta?.coreAddition ?? "",
        keyInsight: meta?.keyInsight ?? "",
        classes,
        functions: methods,
        layer: meta?.layer ?? "tools",
        source,
      });
    }
  } else if (fs.existsSync(AGENTS_DIR)) {
    // 回退：从 agents/ 目录读取 Python 源码
    const agentFiles = fs
      .readdirSync(AGENTS_DIR)
      .filter((f) => f.startsWith("s") && f.endsWith(".py"));

    console.log(`  Found ${agentFiles.length} agent files (Python fallback)`);

    for (const filename of agentFiles) {
      const versionId = filenameToVersionId(filename);
      if (!versionId) {
        console.warn(`  Skipping ${filename}: could not determine version ID`);
        continue;
      }

      const filePath = path.join(AGENTS_DIR, filename);
      const source = fs.readFileSync(filePath, "utf-8");
      const lines = source.split("\n");

      const meta = VERSION_META[versionId];
      const classes = extractClasses(lines);
      const functions = extractFunctions(lines);
      const tools = extractTools(source);
      const loc = countLoc(lines);

      versions.push({
        id: versionId,
        filename,
        title: meta?.title ?? versionId,
        subtitle: meta?.subtitle ?? "",
        loc,
        tools,
        newTools: [],
        coreAddition: meta?.coreAddition ?? "",
        keyInsight: meta?.keyInsight ?? "",
        classes,
        functions,
        layer: meta?.layer ?? "tools",
        source,
      });
    }
  } else {
    console.log("  Source directories not found, skipping extraction.");
    console.log("  Using pre-committed generated data.");
    return;
  }

  // Sort versions according to VERSION_ORDER
  const orderMap = new Map(VERSION_ORDER.map((v, i) => [v, i]));
  versions.sort(
    (a, b) => (orderMap.get(a.id as any) ?? 99) - (orderMap.get(b.id as any) ?? 99)
  );

  // 2. Compute newTools for each version
  for (let i = 0; i < versions.length; i++) {
    const prev = i > 0 ? new Set(versions[i - 1].tools) : new Set<string>();
    versions[i].newTools = versions[i].tools.filter((t) => !prev.has(t));
  }

  // 3. Compute diffs between adjacent versions in LEARNING_PATH
  const diffs: VersionDiff[] = [];
  const versionMap = new Map(versions.map((v) => [v.id, v]));

  for (let i = 1; i < LEARNING_PATH.length; i++) {
    const fromId = LEARNING_PATH[i - 1];
    const toId = LEARNING_PATH[i];
    const fromVer = versionMap.get(fromId);
    const toVer = versionMap.get(toId);

    if (!fromVer || !toVer) continue;

    const fromClassNames = new Set(fromVer.classes.map((c) => c.name));
    const fromFuncNames = new Set(fromVer.functions.map((f) => f.name));
    const fromToolNames = new Set(fromVer.tools);

    diffs.push({
      from: fromId,
      to: toId,
      newClasses: toVer.classes
        .map((c) => c.name)
        .filter((n) => !fromClassNames.has(n)),
      newFunctions: toVer.functions
        .map((f) => f.name)
        .filter((n) => !fromFuncNames.has(n)),
      newTools: toVer.tools.filter((t) => !fromToolNames.has(t)),
      locDelta: toVer.loc - fromVer.loc,
    });
  }

  // 4. Read doc files from locale subdirectories (en/, zh/, ja/)
  const docs: DocContent[] = [];

  if (fs.existsSync(DOCS_DIR)) {
    const localeDirs = ["en", "zh", "ja"];
    let totalDocFiles = 0;

    for (const locale of localeDirs) {
      const localeDir = path.join(DOCS_DIR, locale);
      if (!fs.existsSync(localeDir)) continue;

      const docFiles = fs
        .readdirSync(localeDir)
        .filter((f) => f.endsWith(".md"));

      totalDocFiles += docFiles.length;

      for (const filename of docFiles) {
        const version = extractDocVersion(filename);
        if (!version) {
          console.warn(`  Skipping doc ${locale}/${filename}: could not determine version`);
          continue;
        }

        const filePath = path.join(localeDir, filename);
        const content = fs.readFileSync(filePath, "utf-8");

        const titleMatch = content.match(/^#\s+(.+)$/m);
        const title = titleMatch ? titleMatch[1] : filename;

        docs.push({ version, locale: locale as "en" | "zh" | "ja", title, content });
      }
    }

    console.log(`  Found ${totalDocFiles} doc files across ${localeDirs.length} locales`);
  } else {
    console.warn(`  Docs directory not found: ${DOCS_DIR}`);
  }

  // 5. Write output
  fs.mkdirSync(OUT_DIR, { recursive: true });

  const index: VersionIndex = { versions, diffs };
  const indexPath = path.join(OUT_DIR, "versions.json");
  fs.writeFileSync(indexPath, JSON.stringify(index, null, 2));
  console.log(`  Wrote ${indexPath}`);

  const docsPath = path.join(OUT_DIR, "docs.json");
  fs.writeFileSync(docsPath, JSON.stringify(docs, null, 2));
  console.log(`  Wrote ${docsPath}`);

  // Summary
  console.log("\nExtraction complete:");
  console.log(`  ${versions.length} versions`);
  console.log(`  ${diffs.length} diffs`);
  console.log(`  ${docs.length} docs`);
  for (const v of versions) {
    console.log(
      `    ${v.id}: ${v.loc} LOC, ${v.tools.length} tools, ${v.classes.length} classes, ${v.functions.length} functions`
    );
  }
}

main();
