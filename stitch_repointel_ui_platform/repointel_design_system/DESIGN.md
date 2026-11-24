---
name: RepoIntel Design System
colors:
  surface: '#0f131c'
  surface-dim: '#0f131c'
  surface-bright: '#353943'
  surface-container-lowest: '#0a0e17'
  surface-container-low: '#181b25'
  surface-container: '#1c1f29'
  surface-container-high: '#262a34'
  surface-container-highest: '#31353f'
  on-surface: '#dfe2ef'
  on-surface-variant: '#c3c6d7'
  inverse-surface: '#dfe2ef'
  inverse-on-surface: '#2c303a'
  outline: '#8d90a0'
  outline-variant: '#434655'
  surface-tint: '#b4c5ff'
  primary: '#b4c5ff'
  on-primary: '#002a78'
  primary-container: '#2563eb'
  on-primary-container: '#eeefff'
  inverse-primary: '#0053db'
  secondary: '#b7c8e1'
  on-secondary: '#213145'
  secondary-container: '#3a4a5f'
  on-secondary-container: '#a9bad3'
  tertiary: '#d0bcff'
  on-tertiary: '#3c0091'
  tertiary-container: '#7d4ce7'
  on-tertiary-container: '#f6edff'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#dbe1ff'
  primary-fixed-dim: '#b4c5ff'
  on-primary-fixed: '#00174b'
  on-primary-fixed-variant: '#003ea8'
  secondary-fixed: '#d3e4fe'
  secondary-fixed-dim: '#b7c8e1'
  on-secondary-fixed: '#0b1c30'
  on-secondary-fixed-variant: '#38485d'
  tertiary-fixed: '#e9ddff'
  tertiary-fixed-dim: '#d0bcff'
  on-tertiary-fixed: '#23005c'
  on-tertiary-fixed-variant: '#5516be'
  background: '#0f131c'
  on-background: '#dfe2ef'
  surface-variant: '#31353f'
typography:
  headline-xl:
    fontFamily: Geist
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
    letterSpacing: -0.025em
  headline-xl-mobile:
    fontFamily: Geist
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Geist
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.02em
  headline-lg-mobile:
    fontFamily: Geist
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: -0.015em
  headline-md:
    fontFamily: Geist
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
    letterSpacing: -0.015em
  body-lg:
    fontFamily: Geist
    fontSize: 15px
    fontWeight: '400'
    lineHeight: 22px
    letterSpacing: -0.005em
  body-md:
    fontFamily: Geist
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 18px
    letterSpacing: -0.002em
  body-sm:
    fontFamily: Geist
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0em
  code-lg:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: 0em
  code-md:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0em
  code-sm:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0.02em
  label-md:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '600'
    lineHeight: 14px
    letterSpacing: 0.04em
  label-sm:
    fontFamily: JetBrains Mono
    fontSize: 10px
    fontWeight: '500'
    lineHeight: 12px
    letterSpacing: 0.06em
rounded:
  sm: 0.125rem
  DEFAULT: 0.25rem
  md: 0.375rem
  lg: 0.5rem
  xl: 0.75rem
  full: 9999px
spacing:
  gutter: 1rem
  gutter-dense: 0.5rem
  margin: 1.5rem
  margin-mobile: 1rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 0.75rem
  space-lg: 1.25rem
  space-xl: 2rem
---

## Brand & Style

This design system embodies an evidence-first, calm, and rigorous engineering cockpit designed for deep static analysis, repository telemetry, dependency lineage, and automated architecture intelligence. Inspired by Linear's mechanical discipline, Vercel's stark contrast ratios, GitHub's robust metadata hierarchy, and Sentry's diagnostic clarity, the aesthetic is deliberately dense, unapologetically technical, and free of gratuitous ornamentation.

Every visual token must serve provenance: an engineer examining complex code analysis must instantly discern deterministic evidence from probabilistic AI inferences. 

### Core Attributes
- **Evidence-First Precision:** Visual weight is mapped directly to analytical certainty and signal severity. No decorative ambiguity.
- **Calm Density:** High information density achieved via micro-typography, precise tabular lining figures, and disciplined layout rhythm rather than chaotic clutter.
- **Engineered Restraint:** Surfaces are muted and tectonic; high-chroma colors are strictly quarantined for telemetry states, severity triggers, and execution status.
- **Diagnostic Monospacing:** Code paths, git tree hashes, commit SHAs, SemVer strings, and AST nodes are treated as first-class citizens using a dedicated, high-legibility monospace stack.

## Colors

The system uses a dark-first baseline built on an ultra-deep obsidian slate palette (`#090D16`), providing maximum contrast for complex analytical graphs and syntax trees without inducing optical fatigue. All color pairings maintain minimum 4.5:1 contrast ratios (WCAG AA) for standard text and 3:1 for graphical UI components.

### Semantic Color Matrices

#### 1. Severity Scale
- **Critical (`#F43F5E`):** Blocking CVEs, immediate security vulnerabilities, memory corruption paths. High-vibrancy rose.
- **High (`#F97316`):** Broken builds, logic regressions, unauthenticated route exposure. Pure technical orange.
- **Medium (`#FBBF24`):** Performance cliffs, high-complexity hotspots, deprecated dependency trees. Amber yellow.
- **Low (`#38BDF8`):** Lint violations, non-optimal patterns, dead code branches. Crisp cyan.
- **Info (`#94A3B8`):** Informational annotations, architecture breadcrumbs, metadata notes. Neutral slate.

#### 2. Confidence Tiering (Inference vs. Hard Evidence)
- **Detected (Hard Proof) (`#10B981`):** Deterministic AST match or compiler output. Crisp emerald. Solid indicator icon, full saturation.
- **Likely (Heuristic) (`#6366F1`):** High-confidence statistical heuristic or static model match. Indigo. Medium saturation, subtle boundary dash.
- **Possible (LLM Inferred) (`#A855F7`):** Generative interpretation or emergent pattern. Violet. Paired with micro-sparkle glyph and low-alpha atmospheric tinting.

#### 3. Execution & Analyzer Status
- **Success / Passed:** `#10B981`
- **Running / Queued:** `#0284C7` (pulsing micro-indicator)
- **Warning / Degraded:** `#EAB308`
- **Failed / Panic:** `#EF4444`
- **Skipped / Neutral:** `#475569`

#### 4. Dark vs. Light Mode Adaptation
- **Dark Mode (Default):** Canvas `#090D16`, Surface Base `#0F172A`, Surface Raised `#1E293B`, Borders `#1E293B` to `#334155`. Text runs `#F8FAFC` (primary), `#94A3B8` (secondary), and `#64748B` (muted).
- **Light Mode:** Canvas `#FFFFFF`, Surface Base `#F8FAFC`, Surface Raised `#F1F5F9`, Borders `#E2E8F0` to `#CBD5E1`. Text runs `#0F172A` (primary), `#475569` (secondary), and `#94A3B8` (muted).

## Typography

The typography system relies on a two-tier pairing designed for surgical utility: **Geist** provides modern structural neutrality for navigation, analytical narrative, and control planes; **JetBrains Mono** manages all syntax, paths, SHAs, metric indices, and metadata classifications.

### Technical Typography Guidelines
- **Tabular Figures:** All tabular and quantitative numeric displays must enforce `font-variant-numeric: tabular-nums` to eliminate layout shift across high-frequency real-time updates.
- **Monospace Enclaves:** File trees (`src/auth/session.ts`), Git references (`main@7f2a1b9`), AST paths (`Program.Body[2].CallExpression`), and metric badges must always render in JetBrains Mono.
- **Hierarchy of Scale:** To preserve visual calm in dense interfaces, body content does not exceed 15px. Headlines prioritize micro-kerning over massive scale.

## Layout & Spacing

Layouts are constructed on a strict 4px/8px structural grid designed for fluid horizontal scaling and multi-panel inspector workflows.

### Grid & Viewport Layout
- **Multi-Panel Workspace:** Desktop viewports leverage a 3-column dock pattern: Navigational tree (240px fixed or collapsible), Primary Diagnostic Stage (flexible fluid column), and Inspector Drawer (360px-480px fixed).
- **Responsive Adaptations:**
  - **Desktop (≥ 1280px):** 12-column layout, 16px gutter, 24px outer margin. Inspector drawer open by default.
  - **Tablet (768px - 1279px):** 8-column layout, 12px gutter, 16px outer margin. Inspector converts to an overlay sheet.
  - **Mobile (< 768px):** Single-column stack, 8px gutter, 12px margin. Deep diagnostics switch from side-by-side split view to segmented tab transitions.

## Elevation & Depth

Visual hierarchy uses low-contrast architectural borders and distinct surface elevation tiers instead of heavy blurred shadows.

### Surface Tiers
- **Tier 0 (Backdrop / Deep Canvas):** `#090D16` (Dark) / `#FFFFFF` (Light). Used for base canvas framing.
- **Tier 1 (Surface Panel):** `#0F172A` (Dark) / `#F8FAFC` (Light). Used for primary analytical viewports and code inspection containers. Defined by a 1px boundary (`rgba(255, 255, 255, 0.08)` in dark, `rgba(0, 0, 0, 0.08)` in light).
- **Tier 2 (Surface Elevated / Cards / Drawers):** `#1E293B` (Dark) / `#F1F5F9` (Light). Used for contextual popovers, active drawer inspectors, and floating toolbars. 
- **Tier 3 (Modals / Overlays):** `#334155` (Dark) / `#E2E8F0` (Light) with an ambient technical shadow: `0 8px 24px -4px rgba(0, 0, 0, 0.45)`.

### Border Precision
- Elements maintain a 1px hair-line outline. Heavy borders are strictly prohibited.
- Selected or focused states employ a crisp inner border accent or high-visibility primary focus ring (`0 0 0 1px #2563EB`).

## Shapes

The design system enforces a disciplined, compact shape system (`roundedness: 1` — Soft / 4px base). The interface prioritizes density and mechanical rhythm over soft, circular curves.

- **Micro Controls & Badges:** `rounded` (4px). Applied to chips, tags, status pills, table inline rows, and input boxes.
- **Panels & Container Cards:** `rounded-lg` (8px). Applied to modular inspector panels, code blocks, diff viewers, and terminal pods.
- **Dialogs & Overlay Trays:** `rounded-xl` (12px). Reserved for full floating modal sheets and high-level workflow launchers.
- **Avatars & Pure Status Bullets:** Fully circular (`rounded-full`) exclusively to differentiate human entity indicators from technical node cards.

## Components

### Buttons & Interactive Controls
- **Primary Action:** Solid background (`#2563EB`), high-contrast text (`#FFFFFF`), micro 4px radius, `font-weight: 500`. No heavy shadow; uses a subtle inset top-edge highlight.
- **Secondary Action:** Transparent base with 1px border (`#334155` dark / `#CBD5E1` light), hover elevation shift to Tier 1 surface.
- **Ghost / Minimal (Table actions):** Zero background or border until hover. Monospace icon + label.

### Severity & Status Chips
- Height: 20px (Compact) and 24px (Standard).
- JetBrains Mono, uppercase `label-sm` (10px).
- Styling: Subdued tint background (alpha 12%) paired with a crisp 1px border (alpha 24%) and solid text in the semantic hue.
- Example: **CRITICAL** is background `rgba(244, 63, 94, 0.12)`, border `rgba(244, 63, 94, 0.28)`, label `#F43F5E`.

### Confidence & AI Badge Treatments
- **Hard Evidence Badge:** Standard green border and label (`#10B981`), leading `check-circle` micro-glyph.
- **AI / Inferred Badge:** Violet profile (`#A855F7`). Includes a leading four-point micro-sparkle glyph (`✧`), tinted background gradient (`rgba(168, 85, 247, 0.08)` to `rgba(99, 102, 241, 0.04)`), and an ultra-subtle ambient glow on hover (`box-shadow: 0 0 12px rgba(168, 85, 247, 0.25)`).

### Inputs & Search Bars
- Monospace-capable text inputs with integrated keyboard shortcut hints (`⌘K`, `/`).
- Solid Tier 1 background, crisp 1px neutral border. Focus transitions immediately to `#2563EB` border with 0px blur spread.

### Diagnostic Lists & Data Grids
- Ultra-condensed row heights (32px default, 28px dense).
- Alternating row background omitted in favor of 1px bottom divider lines (`#1E293B` dark / `#E2E8F0` light).
- Interactive hover: instant background tint transition without vertical layout shift.
- Column headers: JetBrains Mono `label-sm`, tracking `0.06em`, uppercase, muted slate color.

### Mermaid & Architecture Diagram Containers
- Enclosed inside Tier 1 panels with an explicit contextual header (graph type, node count, zoom control, export action).
- Canvas background uses a subtle technical grid pattern (1px dots on 16px centers with 4% opacity).
- Graph nodes strictly leverage system severity and confidence tokens; edges render in `#475569` with explicit directional arrows. Full pan/zoom HUD docks into the top-right corner.