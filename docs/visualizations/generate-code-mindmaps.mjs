import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const qihangRoot = path.resolve(scriptDir, "..", "..");
const paiAgentRoot = path.resolve(qihangRoot, "..", "PaiAgent");

const ignoredFolders = new Set([
  ".git",
  ".idea",
  ".qoder",
  ".feisuan",
  "target",
  "node_modules",
  "dist",
  "BOOT-INF",
  "image",
]);

const sourceExtensions = new Set([
  ".java",
  ".ts",
  ".tsx",
  ".vue",
  ".xml",
  ".sql",
  ".yml",
  ".yaml",
  ".json",
  ".md",
]);

const qihangModules = [
  "common",
  "model",
  "mapper",
  "service",
  "security",
  "open-sdk",
  "erp-api",
];

const rootColors = {
  qihang: "#0d6b6f",
  pai: "#23435f",
};

const typeLabels = {
  root: "根节点",
  module: "模块",
  package: "包",
  class: "类",
  interface: "接口",
  enum: "枚举",
  record: "记录",
  annotation: "注解",
  file: "文件",
  controller: "控制器",
  endpoint: "HTTP 接口",
  page: "页面",
  "frontend-api": "前端 API",
  component: "组件",
  store: "状态",
  util: "工具",
  router: "路由",
  layout: "布局",
  config: "配置",
  "mapper-xml": "Mapper XML",
  service: "服务",
  mapper: "Mapper",
  repository: "Repository",
  entity: "实体",
  dto: "DTO",
  test: "测试",
  table: "数据表",
  route: "路由入口",
  doc: "文档",
  resource: "资源",
  skill: "Skill",
  task: "任务",
  executor: "执行器",
  tool: "工具类",
  "engine-core": "引擎核心",
};

function toPosix(value) {
  return value.split(path.sep).join("/");
}

function walkFiles(root, options = {}) {
  const output = [];
  const stack = [root];
  while (stack.length > 0) {
    const current = stack.pop();
    const entries = fs.readdirSync(current, { withFileTypes: true });
    for (const entry of entries) {
      if (entry.isDirectory()) {
        if (!ignoredFolders.has(entry.name)) {
          stack.push(path.join(current, entry.name));
        }
        continue;
      }
      if (!entry.isFile()) continue;
      const ext = path.extname(entry.name).toLowerCase();
      if (options.extensions && !options.extensions.has(ext)) continue;
      const absolute = path.join(current, entry.name);
      const relative = toPosix(path.relative(root, absolute));
      if (options.include && !options.include(relative, absolute)) continue;
      output.push({ absolute, relative, ext });
    }
  }
  return output.sort((a, b) => a.relative.localeCompare(b.relative, "zh-CN"));
}

function node(type, label, meta = "", children = [], extra = {}) {
  return {
    type,
    label,
    meta: meta || "",
    children: children.filter(Boolean),
    ...extra,
  };
}

function ensureMapNode(map, key, factory) {
  if (!map.has(key)) map.set(key, factory());
  return map.get(key);
}

function compactAnnotations(annotations) {
  const allowed = new Set([
    "RestController",
    "Controller",
    "Service",
    "Component",
    "Configuration",
    "ConfigurationProperties",
    "Mapper",
    "Repository",
    "Entity",
    "TableName",
    "Aspect",
    "Scheduled",
    "Transactional",
    "RequestMapping",
    "Validated",
  ]);
  return [...new Set(annotations)].filter((item) => allowed.has(item));
}

function parseJava(file) {
  const text = fs.readFileSync(file.absolute, "utf8");
  const packageMatch = text.match(/package\s+([\w.]+)\s*;/);
  const packageName = packageMatch?.[1] || "default";
  const declarations = [...text.matchAll(
    /\b(?:public\s+|protected\s+|private\s+)?(?:abstract\s+|static\s+|final\s+|sealed\s+|non-sealed\s+)*(class|interface|enum|record|@interface)\s+([A-Za-z_]\w*)/g,
  )].map((match) => ({
    kind: match[1] === "@interface" ? "annotation" : match[1],
    name: match[2],
    index: match.index || 0,
  }));

  const primary = declarations[0] || {
    kind: "file",
    name: path.basename(file.absolute, ".java"),
    index: 0,
  };
  const header = text.slice(0, primary.index);
  const annotationNames = [...header.matchAll(/@([A-Za-z_]\w*)/g)].map(
    (match) => match[1],
  );
  const annotations = compactAnnotations(annotationNames);
  const isController =
    annotations.includes("RestController") || annotations.includes("Controller");
  const endpoints = isController ? parseSpringEndpoints(text, primary.index) : [];

  const nestedTypes = declarations.slice(1).map((item) =>
    node(
      item.kind,
      item.name,
      `内部类型，来源 ${file.relative}`,
      [],
      { path: file.relative },
    ),
  );

  const children = [...nestedTypes];
  for (const endpoint of endpoints) {
    children.push(
      node(
        "endpoint",
        `${endpoint.method} ${endpoint.url}`,
        endpoint.name
          ? `${endpoint.name}，源码 ${file.relative}`
          : `源码 ${file.relative}`,
        [],
        {
          path: file.relative,
          httpMethod: endpoint.method,
        },
      ),
    );
  }

  return {
    kind: primary.kind,
    name: primary.name,
    packageName,
    annotations,
    endpoints,
    file,
    children,
  };
}

function parseSpringEndpoints(text, classIndex) {
  const beforeClass = text.slice(0, classIndex);
  const baseMappings = [
    ...beforeClass.matchAll(/@RequestMapping\s*\(([^)]*)\)/gs),
  ];
  const baseMappingMatch = baseMappings.find(
    (match) => !isInsideJavaComment(beforeClass, match.index || 0),
  );
  const basePath = extractFirstQuoted(baseMappingMatch?.[1] || "");
  const mappingRegex =
    /@(Get|Post|Put|Delete|Patch|Request)Mapping(?:\s*\(([^)]*)\))?/g;
  const endpoints = [];

  for (const match of text.matchAll(mappingRegex)) {
    if ((match.index || 0) < classIndex) continue;
    if (isInsideJavaComment(text, match.index || 0)) continue;
    const type = match[1];
    const args = match[2] || "";
    const method =
      type === "Request"
        ? (args.match(/RequestMethod\.(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)/)?.[1] ||
          "REQUEST")
        : type.toUpperCase();
    const segment = extractFirstQuoted(args);
    const url = joinUrl(basePath, segment);
    const after = text.slice((match.index || 0) + match[0].length);
    const methodMatch = after.slice(0, 1000).match(
      /\b(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?[\w<>?,.\[\]\s]+\s+([A-Za-z_]\w*)\s*\(/,
    );
    endpoints.push({
      method,
      url: url || "/",
      name: methodMatch?.[1] || "",
    });
  }

  return endpoints;
}

function isInsideJavaComment(text, index) {
  const lineStart = text.lastIndexOf("\n", index) + 1;
  const linePrefix = text.slice(lineStart, index);
  if (linePrefix.includes("//")) return true;
  const before = text.slice(0, index);
  const lastOpen = before.lastIndexOf("/*");
  const lastClose = before.lastIndexOf("*/");
  return lastOpen > lastClose;
}

function extractFirstQuoted(value) {
  const match = value.match(/["']([^"']+)["']/);
  return match?.[1] || "";
}

function joinUrl(base, segment) {
  if (!base) return segment || "";
  if (!segment) return base;
  return `/${[base, segment]
    .map((part) => part.replace(/^\/+|\/+$/g, ""))
    .filter(Boolean)
    .join("/")}`;
}

function buildJavaBranch(files, branchLabel, options = {}) {
  const root = node("module", branchLabel, `${files.length} 个 Java 源文件`);
  const packageMap = new Map();
  const seenTypes = new Set();

  for (const file of files) {
    const parsed = parseJava(file);
    if (options.excludeControllers && parsed.endpoints.length > 0) continue;
    if (options.controllersOnly && parsed.endpoints.length === 0) continue;

    const packageNode = ensureMapNode(
      packageMap,
      parsed.packageName,
      () => node("package", parsed.packageName, "Java 包"),
    );
    const annotations =
      parsed.annotations.length > 0
        ? `注解 ${parsed.annotations.map((item) => `@${item}`).join(", ")}`
        : "";
    const endpointText =
      parsed.endpoints.length > 0 ? `${parsed.endpoints.length} 个 HTTP 接口` : "";
    const meta = [parsed.packageName, annotations, endpointText, parsed.file.relative]
      .filter(Boolean)
      .join(" | ");
    const typeKey = `${parsed.kind}:${parsed.packageName}:${parsed.name}:${parsed.file.relative}`;
    if (seenTypes.has(typeKey)) continue;
    seenTypes.add(typeKey);
    packageNode.children.push(
      node(parsed.kind, parsed.name, meta, parsed.children, {
        path: parsed.file.relative,
        packageName: parsed.packageName,
        annotations: parsed.annotations,
      }),
    );
  }

  root.children = [...packageMap.values()]
    .map((packageNode) => {
      packageNode.children.sort((a, b) =>
        `${a.label}:${a.path || ""}`.localeCompare(
          `${b.label}:${b.path || ""}`,
          "zh-CN",
        ),
      );
      packageNode.meta = `${packageNode.children.length} 个类型`;
      return packageNode;
    })
    .sort((a, b) => a.label.localeCompare(b.label, "zh-CN"));

  return root;
}

function classifyFrontend(relative) {
  const value = relative.replace(/\\/g, "/").toLowerCase();
  if (/\/api\/|^api\//.test(value)) return "frontend-api";
  if (/\/pages\/|\/views\//.test(value)) return "page";
  if (/\/components\//.test(value)) return "component";
  if (/\/store\//.test(value)) return "store";
  if (/\/utils?\//.test(value)) return "util";
  if (/\/router\//.test(value)) return "router";
  if (/\/layout\//.test(value)) return "layout";
  return "file";
}

function parseFrontendFile(file) {
  const text = fs.readFileSync(file.absolute, "utf8");
  const children = [];
  const declarations = [
    ...text.matchAll(
      /export\s+(?:default\s+)?(?:abstract\s+)?(class|interface|type|enum)\s+([A-Za-z_]\w*)/g,
    ),
  ].map((match) => ({
    kind: match[1],
    name: match[2],
  }));
  const functions = [
    ...text.matchAll(
      /export\s+(?:async\s+)?(?:function|const|let)\s+([A-Za-z_]\w*)/g,
    ),
  ].map((match) => match[1]);

  for (const item of declarations) {
    children.push(
      node(
        item.kind === "type" ? "interface" : item.kind,
        item.name,
        `前端导出类型，来源 ${file.relative}`,
        [],
        { path: file.relative },
      ),
    );
  }
  for (const name of [...new Set(functions)]) {
    children.push(
      node(
        classifyFrontend(file.relative) === "frontend-api" ? "frontend-api" : "file",
        name,
        `前端导出方法，来源 ${file.relative}`,
        [],
        { path: file.relative },
      ),
    );
  }

  return children;
}

function buildFrontendBranch(files, branchLabel) {
  const root = node("module", branchLabel, `${files.length} 个前端文件`);
  const folderMap = new Map();

  for (const file of files) {
    const type = classifyFrontend(file.relative);
    const dir =
      path.posix.dirname(file.relative) === "."
        ? "根目录"
        : path.posix.dirname(file.relative);
    const folderNode = ensureMapNode(folderMap, dir, () =>
      node("package", dir, "前端目录"),
    );
    const children = type === "frontend-api" ? parseFrontendFile(file) : [];
    folderNode.children.push(
      node(type, path.posix.basename(file.relative), file.relative, children, {
        path: file.relative,
      }),
    );
  }

  root.children = [...folderMap.values()]
    .map((folder) => {
      folder.children.sort((a, b) =>
        a.label.localeCompare(b.label, "zh-CN"),
      );
      folder.meta = `${folder.children.length} 个文件`;
      return folder;
    })
    .sort((a, b) => a.label.localeCompare(b.label, "zh-CN"));
  return root;
}

function buildTreeBranch(rootDir, files, branchLabel, classifier = () => "file") {
  const root = node("module", branchLabel, `${files.length} 个文件`);
  const folderMap = new Map();
  for (const file of files) {
    const dir =
      path.posix.dirname(file.relative) === "."
        ? "根目录"
        : path.posix.dirname(file.relative);
    const folderNode = ensureMapNode(folderMap, dir, () =>
      node("package", dir, "目录"),
    );
    folderNode.children.push(
      node(classifier(file.relative), path.posix.basename(file.relative), file.relative, [], {
        path: file.relative,
      }),
    );
  }
  root.children = [...folderMap.values()]
    .map((folder) => {
      folder.meta = `${folder.children.length} 个文件`;
      return folder;
    })
    .sort((a, b) => a.label.localeCompare(b.label, "zh-CN"));
  return root;
}

function parseSqlTables(sqlFiles) {
  const tables = new Set();
  for (const file of sqlFiles) {
    const text = fs.readFileSync(file.absolute, "utf8");
    for (const match of text.matchAll(
      /CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+[`"]?([A-Za-z_]\w*)[`"]?/gi,
    )) {
      tables.add(match[1]);
    }
  }
  return [...tables].sort((a, b) => a.localeCompare(b, "en"));
}

function buildSqlBranch(sqlFiles, label) {
  const tables = parseSqlTables(sqlFiles);
  const children = tables.map((table) =>
    node("table", table, "SQL CREATE TABLE", [], {
      path: sqlFiles.map((item) => item.relative).join(", "),
    }),
  );
  const fileNodes = sqlFiles.map((file) =>
    node("file", path.posix.basename(file.relative), file.relative, [], {
      path: file.relative,
    }),
  );
  return node(
    "module",
    label,
    `${sqlFiles.length} 个 SQL 文件，${tables.length} 张表`,
    [
      node("module", "数据库表", `${tables.length} 张表`, children),
      node("module", "SQL 文件", `${sqlFiles.length} 个文件`, fileNodes),
    ],
  );
}

function buildRouteTreeFromMenus(menuFile) {
  const payload = JSON.parse(fs.readFileSync(menuFile.absolute, "utf8"));
  const root = node("module", "动态菜单路由", "menus.json");

  function visit(items) {
    return (items || []).map((item) => {
      const title = item.meta?.title || item.name || item.path || "未命名";
      const hidden = item.hidden ? "隐藏" : "可见";
      const children = visit(item.children);
      return node(
        "route",
        title,
        `${hidden} | ${item.path || "/"} | ${item.component || "目录"}`,
        children,
        { path: item.component || item.path || "" },
      );
    });
  }

  root.children = visit(payload.data);
  return root;
}

function parsePaiRoutes(appFile) {
  const text = fs.readFileSync(appFile.absolute, "utf8");
  const routes = [];
  for (const match of text.matchAll(
    /<Route\s+path="([^"]+)"\s+element=\{<([A-Za-z_]\w*)/g,
  )) {
    routes.push({ path: match[1], component: match[2] });
  }
  return node(
    "module",
    "前端静态路由",
    `${routes.length} 条路由`,
    routes.map((route) =>
      node(
        "route",
        route.path,
        `组件 ${route.component}`,
        [],
        { path: route.path },
      ),
    ),
  );
}

function collectStats(tree) {
  const stats = {
    total: 0,
    modules: 0,
    packages: 0,
    classes: 0,
    interfaces: 0,
    endpoints: 0,
    pages: 0,
    frontendApis: 0,
    tables: 0,
    tests: 0,
    docs: 0,
    files: 0,
    types: {},
  };

  function visit(item) {
    stats.total += 1;
    stats.types[item.type] = (stats.types[item.type] || 0) + 1;
    if (item.type === "module") stats.modules += 1;
    if (item.type === "package") stats.packages += 1;
    if (item.type === "class") stats.classes += 1;
    if (item.type === "interface") stats.interfaces += 1;
    if (item.type === "endpoint") stats.endpoints += 1;
    if (item.type === "page") stats.pages += 1;
    if (item.type === "frontend-api") stats.frontendApis += 1;
    if (item.type === "table") stats.tables += 1;
    if (item.type === "test") stats.tests += 1;
    if (item.type === "doc") stats.docs += 1;
    if (["file", "config", "mapper-xml"].includes(item.type)) stats.files += 1;
    item.children.forEach(visit);
  }

  visit(tree);
  return stats;
}

function buildQihangTree() {
  const files = walkFiles(qihangRoot, { extensions: sourceExtensions });
  const javaFiles = files.filter((file) => file.ext === ".java");
  const mainJava = javaFiles.filter(
    (file) => !/(^|\/)src\/test\//.test(file.relative),
  );
  const testJava = javaFiles.filter((file) => /(^|\/)src\/test\//.test(file.relative));
  const frontendFiles = files.filter(
    (file) =>
      file.relative.startsWith("vue3/") &&
      [".ts", ".tsx", ".vue"].includes(file.ext),
  );
  const mapperXml = files.filter(
    (file) =>
      file.ext === ".xml" &&
      /(^|\/)resources\/mapper\//.test(file.relative),
  );
  const sqlFiles = files.filter((file) => file.ext === ".sql");
  const configFiles = files.filter(
    (file) =>
      [".yml", ".yaml"].includes(file.ext) ||
      (file.ext === ".xml" && !mapperXml.includes(file)),
  );
  const docFiles = files.filter(
    (file) =>
      file.ext === ".md" &&
      (file.relative.startsWith("docs/") ||
        file.relative.startsWith("vue3/public/help/")),
  );

  const backendRoot = node("module", "后端多模块工程", "Spring Boot 4.1");
  backendRoot.children = qihangModules
    .map((moduleName) => {
      const moduleFiles = mainJava.filter((file) =>
        file.relative.startsWith(`${moduleName}/`),
      );
      if (moduleFiles.length === 0) return null;
      const moduleNode = buildJavaBranch(moduleFiles, moduleName, {
        excludeControllers: true,
      });
      moduleNode.meta = `${moduleFiles.length} 个主源码 Java 文件`;
      const moduleXml = mapperXml.filter((file) =>
        file.relative.startsWith(`${moduleName}/`),
      );
      if (moduleXml.length > 0) {
        moduleNode.children.push(
          buildTreeBranch(
            qihangRoot,
            moduleXml,
            "Mapper XML",
            () => "mapper-xml",
          ),
        );
      }
      return moduleNode;
    })
    .filter(Boolean);

  const controllerFiles = mainJava.filter(
    (file) =>
      /\/controller\//.test(file.relative) ||
      /Controller\.java$/.test(file.relative),
  );
  const controllerRoot = buildJavaBranch(controllerFiles, "HTTP 控制器与接口", {
    controllersOnly: true,
  });
  controllerRoot.meta = `${controllerFiles.length} 个 Controller`;

  const menuFile = files.find((file) => file.relative === "menus.json");
  const root = node(
    "root",
    "启航电商 ERP 代码思维导图",
    "按模块、包、类/接口、Controller、前端、数据库和配置展开",
    [
      backendRoot,
      controllerRoot,
      buildFrontendBranch(frontendFiles, "Vue 3 前端"),
      node(
        "module",
        "路由与菜单",
        "动态菜单与前端入口",
        menuFile ? [buildRouteTreeFromMenus(menuFile)] : [],
      ),
      buildSqlBranch(sqlFiles, "SQL 与数据库模型"),
      buildTreeBranch(qihangRoot, configFiles, "配置与工程文件", (relative) => {
        if (relative.endsWith("pom.xml")) return "config";
        if (relative.endsWith(".yml") || relative.endsWith(".yaml")) return "config";
        return "file";
      }),
      buildJavaBranch(testJava, "测试代码", { excludeControllers: false }),
      buildTreeBranch(qihangRoot, docFiles, "文档与帮助", () => "doc"),
    ],
  );

  return root;
}

function buildPaiAgentTree() {
  const files = walkFiles(paiAgentRoot, { extensions: sourceExtensions });
  const javaFiles = files.filter((file) => file.ext === ".java");
  const backendMain = javaFiles.filter(
    (file) =>
      file.relative.startsWith("backend/src/main/java/") &&
      !/\/controller\//.test(file.relative),
  );
  const controllerFiles = javaFiles.filter(
    (file) =>
      file.relative.startsWith("backend/src/main/java/") &&
      /\/controller\//.test(file.relative),
  );
  const backendTests = javaFiles.filter((file) =>
    file.relative.startsWith("backend/src/test/java/"),
  );
  const frontendFiles = files.filter((file) =>
    file.relative.startsWith("frontend/src/") &&
    [".ts", ".tsx"].includes(file.ext),
  );
  const resourceFiles = files.filter(
    (file) =>
      file.relative.startsWith("backend/src/main/resources/") &&
      [".sql", ".yml", ".yaml", ".xml", ".md"].includes(file.ext),
  );
  const sqlFiles = resourceFiles.filter((file) => file.ext === ".sql");
  const skillFiles = files.filter((file) =>
    file.relative.includes("/skills/"),
  );
  const docs = files.filter(
    (file) => file.ext === ".md" && file.relative.startsWith("docs/"),
  );

  const backendRoot = buildJavaBranch(
    backendMain,
    "Backend 主代码",
    { excludeControllers: true },
  );
  const controllerRoot = buildJavaBranch(
    controllerFiles,
    "Backend Controller 与 HTTP 接口",
    { controllersOnly: true },
  );
  const appFile = frontendFiles.find((file) => file.relative === "frontend/src/App.tsx");
  const frontendRoot = buildFrontendBranch(frontendFiles, "Frontend React 代码");

  const resourceRoot = buildTreeBranch(
    paiAgentRoot,
    resourceFiles,
    "Backend 资源与迁移",
    (relative) => {
      if (relative.endsWith(".sql")) return "file";
      if (relative.endsWith(".xml")) return "mapper-xml";
      return "config";
    },
  );

  const root = node(
    "root",
    "PaiAgent 代码思维导图",
    "按 Backend、Frontend、接口、执行器、资源、Skill 和测试展开",
    [
      backendRoot,
      controllerRoot,
      frontendRoot,
      node(
        "module",
        "路由",
        "React Router",
        appFile ? [parsePaiRoutes(appFile)] : [],
      ),
      buildSqlBranch(sqlFiles, "MySQL Schema"),
      buildTreeBranch(paiAgentRoot, skillFiles, "Skills 资源", () => "skill"),
      resourceRoot,
      buildJavaBranch(backendTests, "Backend 测试", {
        excludeControllers: false,
      }),
      buildTreeBranch(paiAgentRoot, docs, "项目文档", () => "doc"),
    ],
  );

  return root;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function renderHtml({ title, subtitle, tree, stats, accent, sourceLabel }) {
  const typeRows = Object.entries(stats.types)
    .sort((a, b) => b[1] - a[1])
    .map(
      ([type, count]) =>
        `<button class="type-chip" data-type="${escapeHtml(type)}"><span>${escapeHtml(
          typeLabels[type] || type,
        )}</span><strong>${count}</strong></button>`,
    )
    .join("");

  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escapeHtml(title)}</title>
  <style>
    :root {
      --accent: ${accent};
      --accent-soft: color-mix(in srgb, ${accent} 12%, white);
      --ink: #17232d;
      --muted: #60717e;
      --page: #f3f6f7;
      --panel: #ffffff;
      --line: #d7e0e4;
      --amber: #b96e20;
      --amber-soft: #faeee1;
      --green: #47785a;
      --green-soft: #e8f2eb;
      --red: #a94a4a;
      --red-soft: #f7eaea;
      --radius: 8px;
      --shadow: 0 10px 30px rgba(24, 44, 57, 0.08);
    }
    * { box-sizing: border-box; }
    html { scroll-behavior: smooth; }
    body {
      margin: 0;
      color: var(--ink);
      background:
        linear-gradient(rgba(35, 67, 95, 0.035) 1px, transparent 1px),
        linear-gradient(90deg, rgba(35, 67, 95, 0.035) 1px, transparent 1px),
        var(--page);
      background-size: 30px 30px;
      font-family: "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", Arial, sans-serif;
      line-height: 1.55;
      overflow-x: hidden;
    }
    button, input, select { font: inherit; }
    button { cursor: pointer; }
    h1, h2, h3, p { margin-top: 0; }
    .page {
      width: min(1800px, calc(100% - 28px));
      margin: 0 auto;
      padding: 18px 0 40px;
    }
    .hero {
      display: grid;
      grid-template-columns: minmax(0, 1fr) auto;
      gap: 20px;
      align-items: end;
      border: 1px solid var(--line);
      border-top: 6px solid var(--accent);
      border-radius: var(--radius);
      background: var(--panel);
      box-shadow: var(--shadow);
      padding: 22px 24px;
    }
    .eyebrow {
      display: inline-flex;
      align-items: center;
      gap: 8px;
      color: var(--accent);
      font-size: 12px;
      font-weight: 800;
      text-transform: uppercase;
    }
    .eyebrow::before {
      content: "";
      width: 24px;
      height: 2px;
      background: var(--accent);
    }
    h1 {
      margin: 8px 0 8px;
      font-size: clamp(28px, 4vw, 46px);
      line-height: 1.14;
      letter-spacing: 0;
    }
    .subtitle {
      max-width: 900px;
      margin: 0;
      color: var(--muted);
      font-size: 14px;
    }
    .source-note {
      border-left: 3px solid var(--accent);
      padding-left: 12px;
      color: var(--muted);
      font-size: 12px;
      max-width: 360px;
    }
    .stats {
      display: grid;
      grid-template-columns: repeat(6, minmax(0, 1fr));
      gap: 10px;
      margin: 14px 0;
    }
    .stat {
      min-height: 88px;
      border: 1px solid var(--line);
      border-radius: var(--radius);
      background: rgba(255,255,255,.95);
      padding: 13px;
    }
    .stat strong {
      display: block;
      color: var(--accent);
      font-size: 24px;
      line-height: 1.2;
    }
    .stat span {
      display: block;
      margin-top: 4px;
      color: var(--muted);
      font-size: 12px;
    }
    .workspace {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 330px;
      gap: 14px;
      align-items: start;
    }
    .map-panel, .detail-panel {
      border: 1px solid var(--line);
      border-radius: var(--radius);
      background: var(--panel);
      box-shadow: var(--shadow);
    }
    .map-panel { min-width: 0; }
    .toolbar {
      position: sticky;
      top: 8px;
      z-index: 20;
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
      border-bottom: 1px solid var(--line);
      border-radius: var(--radius) var(--radius) 0 0;
      background: rgba(255,255,255,.97);
      padding: 10px;
      backdrop-filter: blur(10px);
    }
    .search {
      flex: 1 1 280px;
      min-width: 220px;
      height: 38px;
      border: 1px solid var(--line);
      border-radius: 6px;
      padding: 0 11px;
      outline: none;
    }
    .search:focus {
      border-color: var(--accent);
      box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 13%, transparent);
    }
    .tool-btn, .type-chip {
      min-height: 38px;
      border: 1px solid var(--line);
      border-radius: 6px;
      background: #fff;
      color: var(--ink);
      padding: 0 11px;
    }
    .tool-btn:hover, .type-chip:hover { border-color: var(--accent); color: var(--accent); }
    .result-count {
      color: var(--muted);
      font-size: 12px;
      white-space: nowrap;
    }
    .type-strip {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
      border-bottom: 1px solid var(--line);
      padding: 9px 10px;
    }
    .type-chip {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      min-height: 28px;
      padding: 2px 8px;
      font-size: 11px;
    }
    .type-chip.is-active {
      border-color: var(--accent);
      background: var(--accent-soft);
      color: var(--accent);
      font-weight: 700;
    }
    .type-chip strong { font-size: 11px; }
    .tree-scroll {
      max-height: calc(100vh - 290px);
      overflow: auto;
      padding: 14px 16px 28px;
    }
    .tree, .tree ul {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .tree ul {
      margin-left: 16px;
      padding-left: 14px;
      border-left: 1px solid #dce4e7;
    }
    .tree li {
      position: relative;
      margin: 3px 0;
    }
    .tree li::before {
      content: "";
      position: absolute;
      top: 17px;
      left: -14px;
      width: 12px;
      border-top: 1px solid #dce4e7;
    }
    .tree > li::before { display: none; }
    .tree li.collapsed > ul { display: none; }
    .tree li.filtered-out { display: none; }
    .node-row {
      display: flex;
      align-items: flex-start;
      gap: 7px;
      min-width: 0;
      border: 1px solid transparent;
      border-radius: 5px;
      padding: 2px 5px;
      transition: background 120ms ease, border-color 120ms ease;
    }
    .node-row:hover {
      border-color: #dbe4e7;
      background: #f8fafb;
    }
    .node-row.is-selected {
      border-color: var(--accent);
      background: var(--accent-soft);
    }
    .toggle {
      flex: 0 0 22px;
      width: 22px;
      height: 22px;
      margin-top: 1px;
      border: 1px solid #d8e1e4;
      border-radius: 4px;
      background: #fff;
      color: var(--muted);
      line-height: 1;
      padding: 0;
      font-size: 13px;
    }
    .toggle.placeholder {
      visibility: hidden;
      pointer-events: none;
    }
    .node-label {
      min-width: 0;
      border: 0;
      background: transparent;
      color: var(--ink);
      text-align: left;
      padding: 0;
      overflow-wrap: anywhere;
      word-break: break-word;
    }
    .node-label:hover { color: var(--accent); }
    .node-meta {
      color: var(--muted);
      font-size: 11px;
      overflow-wrap: anywhere;
      word-break: break-word;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      min-height: 20px;
      border-radius: 999px;
      padding: 1px 7px;
      font-size: 10px;
      font-weight: 700;
      white-space: nowrap;
    }
    .t-root { color: #fff; background: var(--accent); }
    .t-module { color: var(--accent); background: var(--accent-soft); }
    .t-package { color: #516771; background: #edf2f4; }
    .t-class, .t-controller { color: #285b89; background: #e7f0f8; }
    .t-interface { color: #8a5b18; background: var(--amber-soft); }
    .t-enum, .t-record, .t-annotation { color: #80516b; background: #f3eaf0; }
    .t-endpoint { color: #25664f; background: var(--green-soft); }
    .t-page, .t-component, .t-layout { color: #6c4d8d; background: #f0eaf6; }
    .t-frontend-api, .t-api { color: #2b6a70; background: #e5f2f1; }
    .t-table { color: #8a4d34; background: #f8ece6; }
    .t-test { color: #944343; background: var(--red-soft); }
    .t-file, .t-config, .t-mapper-xml, .t-resource { color: #536772; background: #edf2f4; }
    .detail-panel {
      position: sticky;
      top: 8px;
      overflow: hidden;
    }
    .detail-head {
      border-bottom: 1px solid var(--line);
      padding: 16px;
    }
    .detail-head h2 {
      margin-bottom: 5px;
      font-size: 18px;
      overflow-wrap: anywhere;
    }
    .detail-body {
      display: grid;
      gap: 10px;
      padding: 16px;
    }
    .detail-item {
      border-bottom: 1px solid #edf1f2;
      padding-bottom: 9px;
    }
    .detail-item span {
      display: block;
      color: var(--muted);
      font-size: 11px;
      font-weight: 700;
    }
    .detail-item strong {
      display: block;
      margin-top: 2px;
      font-size: 13px;
      overflow-wrap: anywhere;
      word-break: break-word;
    }
    .empty-detail { color: var(--muted); font-size: 13px; }
    .footer {
      margin-top: 14px;
      color: var(--muted);
      text-align: center;
      font-size: 11px;
    }
    @media (max-width: 1180px) {
      .stats { grid-template-columns: repeat(3, minmax(0, 1fr)); }
      .workspace { grid-template-columns: 1fr; }
      .detail-panel { position: static; }
      .tree-scroll { max-height: 68vh; }
    }
    @media (max-width: 720px) {
      .page { width: min(1800px, calc(100% - 16px)); padding-top: 8px; }
      .hero { grid-template-columns: 1fr; padding: 16px; }
      .stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      h1 { font-size: 28px; }
      .toolbar { position: static; }
      .tree-scroll { max-height: 64vh; padding-left: 10px; padding-right: 10px; }
      .node-meta { display: none; }
      .toggle { flex-basis: 20px; width: 20px; height: 20px; }
    }
    @media print {
      body { background: #fff; }
      .page { width: 100%; padding: 0; }
      .toolbar, .type-strip, .detail-panel { display: none !important; }
      .workspace { display: block; }
      .map-panel { box-shadow: none; }
      .tree-scroll { max-height: none; overflow: visible; }
      .tree li.filtered-out { display: block; }
    }
  </style>
</head>
<body>
  <main class="page">
    <section class="hero">
      <div>
        <span class="eyebrow">${escapeHtml(sourceLabel)} / Code Mind Map</span>
        <h1>${escapeHtml(title)}</h1>
        <p class="subtitle">${escapeHtml(subtitle)}</p>
      </div>
      <div class="source-note">
        节点由源码生成：类/接口按包展开，Controller 方法展开为 HTTP 接口，前端文件保留目录层级，
        SQL 文件展开为数据表。使用搜索和类型筛选可快速定位。
      </div>
    </section>

    <section class="stats">
      <div class="stat"><strong>${stats.total}</strong><span>思维导图节点</span></div>
      <div class="stat"><strong>${stats.classes}</strong><span>类</span></div>
      <div class="stat"><strong>${stats.interfaces}</strong><span>接口</span></div>
      <div class="stat"><strong>${stats.endpoints}</strong><span>HTTP 接口</span></div>
      <div class="stat"><strong>${stats.pages}</strong><span>前端页面</span></div>
      <div class="stat"><strong>${stats.tables}</strong><span>数据表</span></div>
    </section>

    <section class="workspace">
      <div class="map-panel">
        <div class="toolbar">
          <input id="search" class="search" type="search" placeholder="搜索类名、接口路径、页面、数据表或包名">
          <button class="tool-btn" id="expand-all" type="button">展开全部</button>
          <button class="tool-btn" id="expand-two" type="button">展开两层</button>
          <button class="tool-btn" id="collapse-all" type="button">收起全部</button>
          <button class="tool-btn" id="clear-filter" type="button">清除筛选</button>
          <button class="tool-btn" id="print" type="button">打印 / 导出 PDF</button>
          <span class="result-count" id="result-count"></span>
        </div>
        <div class="type-strip" id="type-strip">
          <button class="type-chip is-active" data-type="all"><span>全部</span><strong>${stats.total}</strong></button>
          ${typeRows}
        </div>
        <div class="tree-scroll">
          <ul class="tree" id="tree"></ul>
        </div>
      </div>

      <aside class="detail-panel">
        <div class="detail-head">
          <h2 id="detail-title">选择节点查看详情</h2>
          <span class="badge t-root" id="detail-type">根节点</span>
        </div>
        <div class="detail-body" id="detail-body">
          <p class="empty-detail">点击左侧任意节点，可查看节点类型、源码路径、包名、注解、直属子节点数量等信息。</p>
        </div>
      </aside>
    </section>

    <p class="footer">Generated from current source tree on ${new Date().toISOString().slice(0, 10)}.</p>
  </main>

  <script>
    const treeData = ${JSON.stringify(tree)};
    const typeLabelMap = ${JSON.stringify(typeLabels)};
  </script>
  <script>
    (() => {
      const treeRoot = document.getElementById("tree");
      const search = document.getElementById("search");
      const count = document.getElementById("result-count");
      const detailTitle = document.getElementById("detail-title");
      const detailType = document.getElementById("detail-type");
      const detailBody = document.getElementById("detail-body");
      const typeStrip = document.getElementById("type-strip");
      const nodeRecords = [];
      const recordsById = new Map();
      let activeType = "all";
      let nextId = 0;

      function walkData(item, parent = null, depth = 0, branch = []) {
        const id = nextId++;
        const record = {
          id,
          data: item,
          parent,
          depth,
          branch: [...branch, item.label],
        };
        nodeRecords.push(record);
        recordsById.set(id, record);
        item.children.forEach((child) => walkData(child, record, depth + 1, record.branch));
        record.subtreeEnd = nextId - 1;
        return record;
      }
      walkData(treeData);

      function getChildRecord(record, index) {
        let cursor = record.id + 1;
        for (let i = 0; i < index; i += 1) {
          cursor = getSubtreeEnd(cursor) + 1;
        }
        return recordsById.get(cursor);
      }

      function getSubtreeEnd(id) {
        const record = recordsById.get(id);
        return record.subtreeEnd;
      }

      function buildNode(record) {
        const li = document.createElement("li");
        li.dataset.id = record.id;
        li.dataset.type = record.data.type;
        li.dataset.depth = record.depth;
        record.element = li;

        const row = document.createElement("div");
        row.className = "node-row";
        record.row = row;

        const toggle = document.createElement("button");
        toggle.className = "toggle";
        toggle.type = "button";
        toggle.textContent = record.data.children.length ? "+" : "·";
        if (!record.data.children.length) toggle.classList.add("placeholder");
        row.appendChild(toggle);

        const badge = document.createElement("span");
        badge.className = "badge t-" + record.data.type;
        badge.textContent = typeLabelMap[record.data.type] || record.data.type;
        row.appendChild(badge);

        const label = document.createElement("button");
        label.className = "node-label";
        label.type = "button";
        label.textContent = record.data.label;
        row.appendChild(label);

        if (record.data.meta) {
          const meta = document.createElement("span");
          meta.className = "node-meta";
          meta.textContent = record.data.meta;
          row.appendChild(meta);
        }
        li.appendChild(row);

        toggle.addEventListener("click", (event) => {
          event.stopPropagation();
          if (!record.data.children.length) return;
          li.classList.toggle("collapsed");
          toggle.textContent = li.classList.contains("collapsed") ? "+" : "−";
        });
        label.addEventListener("click", () => selectRecord(record));
        row.addEventListener("click", () => selectRecord(record));

        if (record.data.children.length) {
          const ul = document.createElement("ul");
          record.data.children.forEach((child, index) => {
            ul.appendChild(buildNode(getChildRecord(record, index)));
          });
          li.appendChild(ul);
        }
        return li;
      }

      function setDepth(maxDepth) {
        for (const record of nodeRecords) {
          if (!record.element || !record.data.children.length) continue;
          const collapsed = record.depth >= maxDepth;
          record.element.classList.toggle("collapsed", collapsed);
          const toggle = record.element.querySelector(":scope > .node-row > .toggle");
          if (toggle) toggle.textContent = collapsed ? "+" : "−";
        }
      }

      function expandAll() {
        for (const record of nodeRecords) {
          if (!record.element || !record.data.children.length) continue;
          record.element.classList.remove("collapsed");
          const toggle = record.element.querySelector(":scope > .node-row > .toggle");
          if (toggle) toggle.textContent = "−";
        }
      }

      function collapseAll() {
        for (const record of nodeRecords) {
          if (!record.element || !record.data.children.length) continue;
          const collapsed = record.depth > 0;
          record.element.classList.toggle("collapsed", collapsed);
          const toggle = record.element.querySelector(":scope > .node-row > .toggle");
          if (toggle) toggle.textContent = collapsed ? "+" : "−";
        }
      }

      function selectRecord(record) {
        document.querySelectorAll(".node-row.is-selected").forEach((item) =>
          item.classList.remove("is-selected"),
        );
        record.row.classList.add("is-selected");
        detailTitle.textContent = record.data.label;
        detailType.className = "badge t-" + record.data.type;
        detailType.textContent = typeLabelMap[record.data.type] || record.data.type;
        const annotations = record.data.annotations?.length
          ? record.data.annotations.map((item) => "@" + item).join(", ")
          : "无";
        detailBody.innerHTML = "";
        const rows = [
          ["节点类型", typeLabelMap[record.data.type] || record.data.type],
          ["源码路径", record.data.path || "无"],
          ["包名", record.data.packageName || "无"],
          ["注解", annotations],
          ["直属子节点", String(record.data.children.length)],
          ["树路径", record.branch.join(" / ")],
          ["附加信息", record.data.meta || "无"],
        ];
        rows.forEach(([label, value]) => {
          const div = document.createElement("div");
          div.className = "detail-item";
          const span = document.createElement("span");
          span.textContent = label;
          const strong = document.createElement("strong");
          strong.textContent = value;
          div.append(span, strong);
          detailBody.appendChild(div);
        });
      }

      function searchable(record) {
        return [
          record.data.label,
          record.data.type,
          record.data.meta,
          record.data.path,
          record.data.packageName,
          ...(record.data.annotations || []),
          ...record.branch,
        ]
          .filter(Boolean)
          .join(" ")
          .toLowerCase();
      }

      function applyFilters() {
        const keyword = search.value.trim().toLowerCase();
        const visible = new Set();
        for (const record of nodeRecords) {
          const typeMatch = activeType === "all" || record.data.type === activeType;
          const keywordMatch = !keyword || searchable(record).includes(keyword);
          if (typeMatch && keywordMatch) {
            let current = record;
            while (current) {
              visible.add(current.id);
              current = current.parent;
            }
          }
        }
        for (const record of nodeRecords) {
          if (!record.element) continue;
          record.element.classList.toggle("filtered-out", !visible.has(record.id));
          if (visible.has(record.id) && record.data.children.length) {
            record.element.classList.remove("collapsed");
            const toggle = record.element.querySelector(":scope > .node-row > .toggle");
            if (toggle) toggle.textContent = "−";
          }
        }
        count.textContent =
          "显示 " + visible.size + " / " + nodeRecords.length + " 个节点";
      }

      treeRoot.appendChild(buildNode(nodeRecords[0]));
      typeStrip.addEventListener("click", (event) => {
        const button = event.target.closest(".type-chip");
        if (!button) return;
        activeType = button.dataset.type;
        typeStrip.querySelectorAll(".type-chip").forEach((item) =>
          item.classList.toggle("is-active", item === button),
        );
        applyFilters();
      });
      search.addEventListener("input", applyFilters);
      document.getElementById("expand-all").addEventListener("click", expandAll);
      document.getElementById("collapse-all").addEventListener("click", collapseAll);
      document.getElementById("expand-two").addEventListener("click", () => setDepth(2));
      document.getElementById("clear-filter").addEventListener("click", () => {
        search.value = "";
        activeType = "all";
        typeStrip.querySelectorAll(".type-chip").forEach((item) =>
          item.classList.toggle("is-active", item.dataset.type === "all"),
        );
        applyFilters();
        setDepth(2);
      });
      document.getElementById("print").addEventListener("click", () => window.print());
      setDepth(2);
      applyFilters();
      selectRecord(nodeRecords[0]);
    })();
  </script>
</body>
</html>`;
}

function writeMindMap(rootDir, outputName, config) {
  const tree = config.buildTree();
  const stats = collectStats(tree);
  const html = renderHtml({ ...config, tree, stats });
  const output = path.join(rootDir, "docs", "visualizations", outputName);
  fs.writeFileSync(output, html, "utf8");
  console.log(`${output}: ${stats.total} nodes`);
}

writeMindMap(qihangRoot, "qihang-erp-code-mindmap.html", {
  title: "启航电商 ERP 代码思维导图",
  subtitle:
    "从多模块后端、Controller HTTP 接口、Vue 3 前端页面与 API、数据库表、配置和测试代码展开的全量源码导航图。",
  accent: rootColors.qihang,
  sourceLabel: "Qihang ERP",
  buildTree: buildQihangTree,
});

writeMindMap(paiAgentRoot, "paiagent-code-mindmap.html", {
  title: "PaiAgent 代码思维导图",
  subtitle:
    "覆盖 Backend 控制器、Service、双引擎、Executor、Agent Tool、DTO、Entity、Mapper、Frontend 页面/组件/API、Skill、SQL 和测试。",
  accent: rootColors.pai,
  sourceLabel: "PaiAgent",
  buildTree: buildPaiAgentTree,
});
