# Masroof design system

This document describes the shared tokens and components screens are built from. It is
reference for consistency, not an approval gate: `AGENTS.md` Priorities rank it below
financial correctness, data safety, architecture and a working screen. The code is the
source of truth; the original HTML prototype is not kept in the repository. Tokens live in
`ui-kit/src/main/kotlin/com/baraa/masroof/ui/theme/`; `MasroofDesign.version` is `2026-10-10`.

## One source of visual values

| Concern | Authoritative source |
| --- | --- |
| Light/dark and semantic colors | `MasroofColors.kt`, `MasroofHeroColors.kt` |
| Launcher brand colors | `res/values/masroof_brand.xml` |
| Opacity and font weights | `MasroofDesign.kt` |
| Spacing and geometry | `MasroofReference.kt`, `MasroofSpacing.kt` |
| Typography and font weights | `MasroofTypography.kt`, `MasroofFontFamily.kt`, `MasroofFontWeights` |
| Shapes | `MasroofShapes.kt` |
| Icon geometry | `MasroofIconSizes.kt` |
| Icon meanings | `presentation/common/MasroofIcons.kt` |
| Elevation | `MasroofElevation.kt` |
| Localized terminology | `res/values/strings.xml`, `res/values-en/strings.xml` |

Screens use these tokens through MaterialTheme and shared components. Do not add
numeric dp/sp or color literals in feature screens. `DesignTokenBoundaryTest`
enforces this boundary. Cards use radius 20, controls 12, screen padding 16 and card padding 16 plus its 1-unit border inset,
flat elevation, and IBM Plex Sans Arabic. Filled light-mode buttons use onPrimary
white text. Account blue, card purple and liability amber each have paired
readable foregrounds in both modes.

## Shared components

Reuse a shared component before writing a new one; new shared visuals go in `ui-kit`.
Dashboard summary and detail pages emit into the `ColumnScope` supplied by
`DashboardSummaryScaffold`, which owns scrolling, the period display, loading/error gates
and `MasroofSpacing.sectionGap`. Analysis cards prefer a chart, a large number or a badge;
their shares, totals and countdowns come from `AnalysisPresentationBuilder`, and new chart
geometry lives in `MasroofAnalyticsTokens`. The debug design catalog
(`-PmasroofDesignCatalog=true`) previews shared components; adding to it is optional.

## Digits in every language

The display policy is `application/locale/AppDigits`: all visible digits use
`0123456789`. Arabic remains Arabic with RTL layout and Arabic month names.
`AppLocale.displayLocale` requests the `latn` numbering system; `MoneyUiFormatter`
uses Western digits and separators, omits zero fractional digits for whole amounts and preserves two decimal places for fractional amounts. All app text uses `MasroofText`, which also
normalizes imported names and SMS evidence at display time. Editable public text
uses `AppDigitsVisualTransformation` without modifying stored input or cursor
positions. Password fields keep their masking. Original SMS, financial facts and
backups retain their source values. `AppDigitsBoundaryTest` prevents direct text
bypasses and Eastern digits in display resources; native screen tests check text
in both themes and the Arabic date picker.

## Contextual explanations

`presentation/common/MasroofGuidance.kt` is the only contextual-help component.
Use `MasroofGuidanceButton` beside the subject, or `MasroofGuidanceLabel` for a
label and its help icon. The neutral circled exclamation is mapped in MasroofIcons.
`MasroofGuidanceStyle` exposes the icon size, popover width, padding, shape,
elevation and edge spacing. Colors and typography use the
shared MaterialTheme. The small anchored popover fits the window in RTL and LTR,
flips above its subject when necessary, and dismisses on outside press or Back.
Explanations are hidden until requested and never invoke a parent action.

Use this pattern for chart scope, averaging basis, account calculations, shared
credit dues, period adjustments, filters, registry tracking, backup passphrases,
re-analysis, updates, privacy, logs and restore help. Essential status, errors,
confirmation text and identifying data remain visible. The debug catalog includes
the shared help component. `MasroofGuidanceBoundaryTest` prevents inline hint text.

## Chart interaction

Which transactions each chart counts is a financial rule, documented in
`docs/ARCHITECTURE.md` (Dashboard chart scopes). This section covers only interaction.

`MasroofChartScopeTabs` and `MasroofChartAverageLegend` live in common components; their
geometry is in `MasroofReference`. The selected date and amount are visible above the chart.
Tap or scrub the line to choose a day; previous/next are screen-reader actions. Chronological
points start on the right in Arabic and on the left in English, and date endpoints follow
the same direction. Touch coordinates are mapped back to the logical date index before
showing that date's amount. The dashed average line sits at the scope's precomputed mean,
with a matching legend and accessible description.

## Verification

`ProductionPageRenderSmokeTest` renders the production page functions with synthetic
financial fixtures across 32 pages, Arabic/English and light/dark. It is a render smoke
test: it asserts each page renders, uses Western digits and keeps the Home shortcut row
layout, and saves captures for human review. It does not compare pixels against a
reference image.

`AverageLinePixelContractTest` checks the dashed average line at subpixel phases in both
themes and several densities, integrating coverage across the stroke band rather than
expecting one fully opaque pixel row. The Home interaction test applies the same contract
to the real chart, with the mean computed independently from its data.

## Text fitting and documentation captures

`MasroofFitText` scales explicit money spans together with the base font. Shared tab labels
use one common fit scale; below the minimum, money wraps instead of truncating.

The README screenshots in `docs/screenshots/` come from the render smoke test's synthetic
fixtures. Capture both languages with the same fixture and theme; never use real account
evidence or hand-edit financial values in screenshots.
