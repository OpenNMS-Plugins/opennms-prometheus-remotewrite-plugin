<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import Dialog from 'primevue/dialog'

// ── Types ─────────────────────────────────────────────────────────────────────

interface ConfigDto {
  writeUrl: string
  readUrl: string
  maxConcurrentHttpConnections: number
  writeTimeoutInMs: number
  readTimeoutInMs: number
  metricCacheSize: number
  externalTagsCacheSize: number
  bulkheadMaxWaitDuration: number
  bulkheadUnlimited: boolean
  maxSeriesLookback: number
  organizationId: string
}
interface TestConnectionResult {
  endpoint: string; url: string; reachable: boolean
  statusCode: number; durationMs: number; detail: string
}
interface GaugeStat { name: string; value: unknown }
interface MeterStat {
  name: string; count: number; meanRate: number
  oneMinuteRate: number; fiveMinuteRate: number; fifteenMinuteRate: number
}
interface Stats { gauges: GaugeStat[]; meters: MeterStat[] }
interface NodeSeriesCount { node: string; series: number }
interface SeriesCount {
  totalSeries: number
  nodesWritingData: number
  seriesWithoutNodeTag: number
  matchingNodes: number
  offset: number
  limit: number
  byNode: NodeSeriesCount[]
}
interface ValueSuggest { values: string[]; totalMatching: number }
interface TagMatcher { id: number; key: string; type: string; value: string }
interface MetricDto {
  key: string; name: string
  intrinsicTags: Record<string, string>
  metaTags: Record<string, string>
  externalTags: Record<string, string>
}
interface HealthIssue { severity: string; title: string; detail: string; remedy: string }
interface Health {
  functional: boolean
  timeseriesStrategy: string
  runningStrategy: string
  configuredStrategy: string
  strategySource: string
  integrationStrategyActive: boolean
  pendingRestart: boolean
  integrationManaged: boolean
  storageServiceActive: boolean
  opennmsHome: string
  issues: HealthIssue[]
}

// ── API helper ────────────────────────────────────────────────────────────────

const API = '/opennms/rest/prometheus-remotewrite'

async function apiFetch<T>(path: string, options: RequestInit = {}): Promise<T> {
  const resp = await fetch(API + path, {
    headers: { 'Content-Type': 'application/json', ...options.headers as Record<string, string> },
    ...options,
  })
  if (resp.status === 204) return null as T
  const text = await resp.text()
  let body: unknown
  try { body = JSON.parse(text) } catch { body = text }
  if (!resp.ok) throw new Error(typeof body === 'string' ? body : JSON.stringify(body))
  return body as T
}

// ── Toast ─────────────────────────────────────────────────────────────────────

interface ToastMsg { id: number; severity: string; summary: string; detail: string }
const toasts = ref<ToastMsg[]>([])
let toastSeq = 0
function toast(severity: string, summary: string, detail: string) {
  const id = ++toastSeq
  toasts.value.push({ id, severity, detail, summary })
  setTimeout(() => { toasts.value = toasts.value.filter(t => t.id !== id) }, 4000)
}
function toastStyle(severity: string) {
  return {
    padding: '.625rem 1rem', borderRadius: '4px',
    background: severity === 'success' ? '#e6f4ea' : severity === 'error' ? '#fce8e6' : '#fef9e7',
    color: severity === 'success' ? '#137333' : severity === 'error' ? '#c5221f' : '#7a6000',
    border: `1px solid ${severity === 'success' ? '#b7dfc3' : severity === 'error' ? '#f6aea9' : '#f5e0a2'}`,
    fontSize: '.875rem', minWidth: '240px', boxShadow: '0 2px 8px rgba(0,0,0,.12)',
  }
}

// ── Help toggles ──────────────────────────────────────────────────────────────

const showHelp = reactive<Record<string, boolean>>({})
const toggleHelp = (key: string) => { showHelp[key] = !showHelp[key] }

// ── Health / preflight gate ─────────────────────────────────────────────────────
//
// Nothing in this UI is shown until we have verified the plugin can actually do its
// job. If any precondition fails we render a clean instruction page instead of the
// configuration UI — never empty/broken tabs, never an action that can't succeed.

const health = ref<Health | null>(null)
const healthChecked = ref(false)      // have we completed at least one /health attempt?
const healthError = ref<string | null>(null)  // set when /health itself can't be reached
const checkingHealth = ref(false)

async function loadHealth() {
  checkingHealth.value = true
  try {
    health.value = await apiFetch<Health>('/health')
    healthError.value = null
  } catch (e: unknown) {
    // The endpoint itself failing is the most severe diagnostic of all.
    health.value = null
    healthError.value = (e as Error).message || 'The plugin REST API did not respond.'
  } finally {
    healthChecked.value = true
    checkingHealth.value = false
  }
}

// Top-level gate for the whole UI.
const uiState = computed<'loading' | 'unreachable' | 'blocked' | 'ready'>(() => {
  if (!healthChecked.value) return 'loading'
  if (healthError.value !== null) return 'unreachable'
  if (!health.value || !health.value.functional) return 'blocked'
  return 'ready'
})

// ── Enable / disable the integration strategy (system-wide, restart-required) ────

// Exact file + content the backend writes (shown in the confirmation for transparency).
// Uses the real opennms.home from /health so the path is concrete for this install.
const opennmsHome = computed(() => health.value?.opennmsHome || '$OPENNMS_HOME')
const managedFilePath = computed(() => `${opennmsHome.value}/etc/opennms.properties.d/prometheus-remotewrite.properties`)
const MANAGED_FILE_CONTENT =
  'org.opennms.timeseries.strategy=integration\n' +
  'org.opennms.timeseries.tin.metatags.tag.node=${node:label}\n' +
  'org.opennms.timeseries.tin.metatags.tag.location=${node:location}\n' +
  'org.opennms.timeseries.tin.metatags.tag.geohash=${node:geohash}\n' +
  'org.opennms.timeseries.tin.metatags.tag.ifDescr=${interface:if-description}'
// Copy-pasteable restart commands for both common install types.
const restartCommands = computed(() =>
  'sudo systemctl restart opennms          # packaged (systemd) install\n' +
  `${opennmsHome.value}/bin/opennms restart   # manual / tarball install`)

const confirmVisible = ref(false)
const confirmMode = ref<'enable' | 'disable'>('enable')
const applyingIntegration = ref(false)
function openConfirm(mode: 'enable' | 'disable') { confirmMode.value = mode; confirmVisible.value = true }

async function runConfirm() {
  applyingIntegration.value = true
  const path = confirmMode.value === 'enable' ? '/integration/enable' : '/integration/disable'
  try {
    const r = await apiFetch<{ ok: boolean; message: string }>(path, { method: 'POST' })
    toast(r.ok ? 'success' : 'error',
      r.ok ? (confirmMode.value === 'enable' ? 'Integration enabled' : 'Reverted') : 'Failed',
      r.message)
    confirmVisible.value = false
    await loadHealth()
  } catch (e: unknown) {
    toast('error', 'Failed', (e as Error).message)
  } finally { applyingIntegration.value = false }
}

// ── Tabs ──────────────────────────────────────────────────────────────────────

const activeTab = ref<'settings' | 'stats' | 'explorer'>('settings')

// ── Settings ──────────────────────────────────────────────────────────────────

const form = reactive<ConfigDto>({
  writeUrl: 'http://localhost:9009/api/prom/push',
  readUrl: 'http://localhost:9009/prometheus/api/v1',
  maxConcurrentHttpConnections: 100,
  writeTimeoutInMs: 5000,
  readTimeoutInMs: 5000,
  metricCacheSize: 1000,
  externalTagsCacheSize: 1000,
  bulkheadMaxWaitDuration: 0,
  bulkheadUnlimited: true,
  maxSeriesLookback: 7776000,
  organizationId: '',
})
const loadingConfig = ref(false)
const savingConfig = ref(false)
const formErrors = reactive<Record<string, string>>({ writeUrl: '', readUrl: '' })

async function loadConfig() {
  loadingConfig.value = true
  try {
    Object.assign(form, await apiFetch<ConfigDto>('/config'))
    savedEndpointKey.value = endpointKey.value
  } catch (e: unknown) {
    toast('error', 'Error', 'Failed to load config: ' + (e as Error).message)
  } finally { loadingConfig.value = false }
}

function validateConfig(): boolean {
  formErrors.writeUrl = form.writeUrl.trim() ? '' : 'Write URL is required'
  formErrors.readUrl = form.readUrl.trim() ? '' : 'Read URL is required'
  return !formErrors.writeUrl && !formErrors.readUrl
}

async function saveConfig() {
  if (!validateConfig()) return
  savingConfig.value = true
  try {
    await apiFetch('/config', { method: 'PUT', body: JSON.stringify(form) })
    savedEndpointKey.value = endpointKey.value
    toast('success', 'Saved', 'Configuration updated. Changes apply on the next config reload.')
  } catch (e: unknown) {
    toast('error', 'Error', (e as Error).message)
  } finally { savingConfig.value = false }
}

// Lookback shown as a friendly days field, stored as seconds
const lookbackDays = ref(90)
function syncLookbackFromSeconds() { lookbackDays.value = Math.round(form.maxSeriesLookback / 86400) }
function syncLookbackToSeconds() { form.maxSeriesLookback = Math.max(0, Math.round(lookbackDays.value * 86400)) }

// ── Enable-integration gate ─────────────────────────────────────────────────────
// Enabling is a system-wide switch, so it is only allowed once we have PROVEN the backend
// accepts metrics — a successful test WRITE — for the exact endpoints that are currently SAVED.
// Editing any endpoint (or not saving it) invalidates the proof and re-locks the button.
const endpointKey = computed(() => `${form.writeUrl} ${form.readUrl} ${form.organizationId ?? ''}`)
const verifiedEndpointKey = ref<string | null>(null)  // set when a test write succeeds
const savedEndpointKey = ref<string | null>(null)     // set on load and on save
const backendVerified = computed(() => verifiedEndpointKey.value !== null && verifiedEndpointKey.value === endpointKey.value)
const endpointsSaved = computed(() => savedEndpointKey.value !== null && savedEndpointKey.value === endpointKey.value)
const canEnableIntegration = computed(() => backendVerified.value && endpointsSaved.value)
const enableBlockedReason = computed(() => {
  if (!backendVerified.value) return 'Run “Send test write” and confirm the backend accepts a sample (for the current endpoints) before enabling.'
  if (!endpointsSaved.value) return 'Save the endpoints first, so integration uses the URLs you just verified.'
  return ''
})

// Connection test (reachability + read-API validity; no side effects)
const testing = ref(false)
const testResults = ref<TestConnectionResult[] | null>(null)
async function testConnection() {
  testing.value = true
  testResults.value = null
  writeTestResult.value = null
  try {
    testResults.value = await apiFetch<TestConnectionResult[]>('/test-connection', {
      method: 'POST',
      body: JSON.stringify({ writeUrl: form.writeUrl, readUrl: form.readUrl, organizationId: form.organizationId }),
    })
  } catch (e: unknown) {
    toast('error', 'Test failed', (e as Error).message)
  } finally { testing.value = false }
}

// Test write — actually pushes ONE synthetic sample (side effect: a test metric lands in the TSDB)
const testingWrite = ref(false)
const writeTestResult = ref<TestConnectionResult | null>(null)
async function sendTestWrite() {
  testingWrite.value = true
  writeTestResult.value = null
  try {
    writeTestResult.value = await apiFetch<TestConnectionResult>('/test-write', {
      method: 'POST',
      body: JSON.stringify({ writeUrl: form.writeUrl, readUrl: form.readUrl, organizationId: form.organizationId }),
    })
    // A successful write proves THESE endpoints accept metrics — unlocks Enable (once saved).
    if (writeTestResult.value?.reachable) verifiedEndpointKey.value = endpointKey.value
  } catch (e: unknown) {
    toast('error', 'Test write failed', (e as Error).message)
  } finally { testingWrite.value = false }
}

// ── Statistics ────────────────────────────────────────────────────────────────

const stats = ref<Stats | null>(null)
const loadingStats = ref(false)
const autoRefresh = ref(false)
let statsTimer: ReturnType<typeof setInterval> | null = null

async function loadStats() {
  loadingStats.value = true
  try {
    stats.value = await apiFetch<Stats>('/stats')
  } catch (e: unknown) {
    toast('error', 'Error', 'Failed to load stats: ' + (e as Error).message)
  } finally { loadingStats.value = false }
}

// Stored-series counts (total + per node) — the "is data actually being written,
// and for which nodes?" view. Search + pagination are SERVER-side, so this stays
// responsive with tens of thousands of nodes.
const NODE_PAGE_SIZE = 25
const seriesCount = ref<SeriesCount | null>(null)
const loadingSeriesCount = ref(false)
const nodeTableSearch = ref('')
const nodeTableOffset = ref(0)
let nodeTableDebounce: ReturnType<typeof setTimeout> | null = null

async function loadSeriesCount() {
  loadingSeriesCount.value = true
  try {
    const params = `contains=${encodeURIComponent(nodeTableSearch.value.trim())}`
      + `&offset=${nodeTableOffset.value}&limit=${NODE_PAGE_SIZE}`
    seriesCount.value = await apiFetch<SeriesCount>('/series-count?' + params)
  } catch (e: unknown) {
    // non-fatal: counters still render without the series section
    seriesCount.value = null
  } finally { loadingSeriesCount.value = false }
}
function onNodeTableSearch() {
  if (nodeTableDebounce) clearTimeout(nodeTableDebounce)
  nodeTableDebounce = setTimeout(() => { nodeTableOffset.value = 0; loadSeriesCount() }, 300)
}
function nodeTablePage(delta: number) {
  const sc = seriesCount.value
  if (!sc) return
  const next = nodeTableOffset.value + delta * NODE_PAGE_SIZE
  if (next < 0 || next >= sc.matchingNodes) return
  nodeTableOffset.value = next
  loadSeriesCount()
}
function refreshStats() { loadStats(); loadSeriesCount() }
function toggleAutoRefresh() {
  autoRefresh.value = !autoRefresh.value
  if (statsTimer) { clearInterval(statsTimer); statsTimer = null }
  if (autoRefresh.value) statsTimer = setInterval(refreshStats, 5000)
}
function fmtNum(n: unknown): string {
  if (typeof n !== 'number') return String(n)
  if (Number.isInteger(n)) return n.toLocaleString()
  return n.toFixed(3)
}
function gaugeLabel(name: string): string {
  const map: Record<string, string> = {
    connectionCount: 'Open connections',
    idleConnectionCount: 'Idle connections',
    queuedCallsCount: 'Queued calls',
    runningCallsCount: 'Running calls',
    availableConcurrentCalls: 'Available concurrent calls',
    maxAllowedConcurrentCalls: 'Max allowed concurrent calls',
  }
  return map[name] || name
}
function meterLabel(name: string): string {
  const map: Record<string, string> = {
    samplesWritten: 'Samples written',
    samplesLost: 'Samples lost',
    extTagsModified: 'External tags modified',
    extTagsCacheUsed: 'External tags cache hits',
    extTagsCacheMissed: 'External tags cache misses',
    extTagPutTransactionFailed: 'External tag write failures',
  }
  return map[name] || name
}

// ── Metric Explorer ───────────────────────────────────────────────────────────

const MATCHER_TYPES = [
  { value: 'EQUALS', label: '= (equals)' },
  { value: 'NOT_EQUALS', label: '!= (not equals)' },
  { value: 'EQUALS_REGEX', label: '=~ (regex)' },
  { value: 'NOT_EQUALS_REGEX', label: '!~ (not regex)' },
]
let matcherSeq = 1
const matchers = ref<TagMatcher[]>([{ id: matcherSeq++, key: 'name', type: 'EQUALS', value: '' }])
const querying = ref(false)
const queryResults = ref<MetricDto[] | null>(null)
const expandedMetric = ref<string | null>(null)

function addMatcher() { matchers.value.push({ id: matcherSeq++, key: '', type: 'EQUALS', value: '' }) }
function removeMatcher(id: number) {
  matchers.value = matchers.value.filter(m => m.id !== id)
  if (matchers.value.length === 0) addMatcher()
}
function toggleMetric(key: string) { expandedMetric.value = expandedMetric.value === key ? null : key }
function tagCount(m: MetricDto): number {
  return Object.keys(m.intrinsicTags).length + Object.keys(m.metaTags).length + Object.keys(m.externalTags).length
}

async function runQuery() {
  const valid = matchers.value.filter(m => m.key.trim())
  if (valid.length === 0) { toast('error', 'Error', 'Add at least one tag matcher with a key.'); return }
  querying.value = true
  queryResults.value = null
  expandedMetric.value = null
  try {
    queryResults.value = await apiFetch<MetricDto[]>('/query', {
      method: 'POST',
      body: JSON.stringify(valid.map(m => ({ key: m.key.trim(), type: m.type, value: m.value }))),
    })
  } catch (e: unknown) {
    toast('error', 'Query failed', (e as Error).message)
  } finally { querying.value = false }
}

// One-click starter query for the "I don't know what to type" moment.
function tryExample() {
  matchers.value = [{ id: matcherSeq++, key: 'name', type: 'EQUALS_REGEX', value: '.*Cpu.*' }]
  runQuery()
}

// ── Node quick filter ─────────────────────────────────────────────────────────
// One searchable typeahead to answer "is node X getting data written?" without
// knowing the tag grammar. Suggestions are filtered SERVER-side (contains + limit),
// so it works with tens of thousands of nodes. Picking a node sets/replaces the
// `node =` matcher (node is the meta tag written by the integration layer) and runs
// the query; editing the matcher rows updates the box.

const appliedNodeFilter = computed<string>(
  () => matchers.value.find(m => m.key.trim() === 'node' && m.type === 'EQUALS')?.value ?? '')
const nodeSearch = ref('')
watch(appliedNodeFilter, (v) => { nodeSearch.value = v })

const nodeSuggest = ref<ValueSuggest | null>(null)
const nodeSuggestLoading = ref(false)
const nodeSuggestOpen = ref(false)
const nodeSuggestIndex = ref(-1)
let nodeDebounce: ReturnType<typeof setTimeout> | null = null

function requestNodeSuggestions(query: string) {
  if (nodeDebounce) clearTimeout(nodeDebounce)
  nodeDebounce = setTimeout(async () => {
    nodeSuggestLoading.value = true
    try {
      nodeSuggest.value = await apiFetch<ValueSuggest>(
        `/suggest/values?key=node&contains=${encodeURIComponent(query.trim())}&limit=${SUGGEST_LIMIT}`)
    } catch { nodeSuggest.value = null }
    finally { nodeSuggestLoading.value = false }
  }, 250)
}
function openNodeSuggest() {
  nodeSuggestOpen.value = true
  nodeSuggestIndex.value = -1
  requestNodeSuggestions(nodeSearch.value)
}
function closeNodeSuggest() { nodeSuggestOpen.value = false; nodeSuggestIndex.value = -1 }
function onNodeBlur() {
  closeNodeSuggest()
  nodeSearch.value = appliedNodeFilter.value // revert unpicked text to what is applied
}
function applyNodeFilter(v: string) {
  matchers.value = matchers.value.filter(m => m.key.trim() !== 'node')
  if (v) matchers.value.push({ id: matcherSeq++, key: 'node', type: 'EQUALS', value: v })
  if (matchers.value.length === 0) addMatcher()
  nodeSearch.value = v
  if (matchers.value.some(m => m.key.trim())) runQuery()
}
function pickNode(n: string) { applyNodeFilter(n); closeNodeSuggest() }
function clearNodeFilter() { applyNodeFilter(''); closeNodeSuggest() }
function nodeKeydown(e: KeyboardEvent) {
  const options = nodeSuggest.value?.values ?? []
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (!nodeSuggestOpen.value) { openNodeSuggest(); return }
    nodeSuggestIndex.value = (nodeSuggestIndex.value + 1) % Math.max(1, options.length)
  } else if (e.key === 'ArrowUp' && nodeSuggestOpen.value) {
    e.preventDefault()
    nodeSuggestIndex.value = nodeSuggestIndex.value <= 0 ? options.length - 1 : nodeSuggestIndex.value - 1
  } else if (e.key === 'Enter') {
    e.preventDefault()
    if (nodeSuggestOpen.value && nodeSuggestIndex.value >= 0 && options[nodeSuggestIndex.value]) {
      pickNode(options[nodeSuggestIndex.value])
    } else {
      applyNodeFilter(nodeSearch.value.trim()) // exact label typed by hand
      closeNodeSuggest()
    }
  } else if (e.key === 'Escape' || e.key === 'Tab') {
    closeNodeSuggest()
  }
}

// ── Explorer autocomplete ─────────────────────────────────────────────────────
// Suggestions come from the backend's own label index (via /suggest/*), so they always
// reflect what is actually stored. Keys are few and fetched once; VALUES are searched
// server-side (contains + limit, debounced) so keys with huge cardinality (e.g. `node`
// with 50k values) never ship their full list to the browser.
// Fetch failures are silent — typing must keep working without suggestions.

const SUGGEST_LIMIT = 50
const tagKeys = ref<string[] | null>(null)
const tagKeysLoading = ref(false)
const valueSuggest = ref<ValueSuggest | null>(null)
const valueSuggestLoading = ref(false)
let valueDebounce: ReturnType<typeof setTimeout> | null = null
const suggest = reactive<{ matcherId: number | null; field: 'key' | 'value' | null; index: number }>(
  { matcherId: null, field: null, index: -1 })

async function loadTagKeys() {
  if (tagKeys.value !== null || tagKeysLoading.value) return
  tagKeysLoading.value = true
  try { tagKeys.value = await apiFetch<string[]>('/suggest/keys') }
  catch { /* silent — no suggestions, typing still works */ }
  finally { tagKeysLoading.value = false }
}
function requestValueSuggestions(key: string, query: string) {
  if (valueDebounce) clearTimeout(valueDebounce)
  valueDebounce = setTimeout(async () => {
    valueSuggestLoading.value = true
    try {
      valueSuggest.value = await apiFetch<ValueSuggest>(
        `/suggest/values?key=${encodeURIComponent(key)}&contains=${encodeURIComponent(query.trim())}&limit=${SUGGEST_LIMIT}`)
    } catch { valueSuggest.value = null }
    finally { valueSuggestLoading.value = false }
  }, 250)
}
function openSuggest(m: TagMatcher, field: 'key' | 'value') {
  if (suggest.matcherId !== m.id || suggest.field !== field) valueSuggest.value = null
  suggest.matcherId = m.id; suggest.field = field; suggest.index = -1
  if (field === 'key') loadTagKeys()
  else if (m.key.trim()) requestValueSuggestions(m.key.trim(), m.value)
}
function closeSuggest() {
  if (valueDebounce) { clearTimeout(valueDebounce); valueDebounce = null }
  suggest.matcherId = null; suggest.field = null; suggest.index = -1
}

const suggestPool = computed<string[]>(() => {
  const m = matchers.value.find(x => x.id === suggest.matcherId)
  if (!m || !suggest.field) return []
  if (suggest.field === 'key') {
    const q = m.key.trim().toLowerCase()
    const pool = tagKeys.value ?? []
    return q ? pool.filter(s => s.toLowerCase().includes(q)) : pool
  }
  return valueSuggest.value?.values ?? [] // already filtered + capped server-side
})
const suggestions = computed<string[]>(() => suggestPool.value.slice(0, SUGGEST_LIMIT))
// how many values match in total (drives the "showing X of Y" hint)
const suggestTotal = computed<number>(() =>
  suggest.field === 'value' ? (valueSuggest.value?.totalMatching ?? 0) : suggestPool.value.length)
const suggestLoading = computed(() =>
  suggest.field === 'key' ? tagKeysLoading.value : valueSuggestLoading.value)
function suggestOpenFor(m: TagMatcher, field: 'key' | 'value'): boolean {
  return suggest.matcherId === m.id && suggest.field === field
    && (suggestions.value.length > 0 || suggestLoading.value)
}
function pickSuggestion(s: string) {
  const m = matchers.value.find(x => x.id === suggest.matcherId)
  if (!m) return
  if (suggest.field === 'key') m.key = s
  else m.value = s
  closeSuggest()
}
function suggestKeydown(e: KeyboardEvent, m: TagMatcher, field: 'key' | 'value') {
  const open = suggest.matcherId === m.id && suggest.field === field && suggestions.value.length > 0
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (!open) { openSuggest(m, field); return }
    suggest.index = (suggest.index + 1) % suggestions.value.length
  } else if (e.key === 'ArrowUp' && open) {
    e.preventDefault()
    suggest.index = suggest.index <= 0 ? suggestions.value.length - 1 : suggest.index - 1
  } else if (e.key === 'Enter') {
    if (open && suggest.index >= 0) { e.preventDefault(); pickSuggestion(suggestions.value[suggest.index]) }
    else { closeSuggest(); runQuery() }
  } else if (e.key === 'Escape' || e.key === 'Tab') {
    closeSuggest()
  }
}

// ── Lifecycle ─────────────────────────────────────────────────────────────────

onMounted(async () => {
  // Preflight first. Load the saved config whenever the REST API is reachable — in the
  // 'ready' state it populates the editable form, and in the 'blocked' state it lets the
  // operator verify the saved backend endpoints before turning integration on.
  await loadHealth()
  if (uiState.value === 'ready' || uiState.value === 'blocked') {
    await loadConfig()
    syncLookbackFromSeconds()
  }
})

// Tabs load their data on activation — no "click Refresh to see anything" state.
watch(activeTab, (tab) => {
  if (uiState.value !== 'ready') return
  if (tab === 'stats') {
    refreshStats()
  } else if (tab === 'explorer') {
    requestNodeSuggestions('') // warm the node quick filter's first page
  }
})
onUnmounted(() => { if (statsTimer) clearInterval(statsTimer) })
</script>

<template>
  <div style="font-family: var(--feather-font-family, 'Open Sans', system-ui, sans-serif); color: var(--feather-primary-text-on-surface, rgba(10,12,27,.9)); padding: 1.5rem; max-width: 1100px;">

    <!-- Header -->
    <div style="display:flex; align-items:flex-start; justify-content:space-between; margin-bottom:1.25rem;">
      <div>
        <h1 style="font-size:1.5rem; font-weight:600; margin:0 0 .25rem;">Prometheus RemoteWrite</h1>
        <p style="margin:0; font-size:.875rem; color:var(--feather-secondary-text-on-surface,rgba(10,12,27,.6));">
          Time-series storage that writes &amp; reads metrics via the Prometheus
          <code>remote_write</code> / <code>remote_read</code> protocol.
        </p>
      </div>
    </div>

    <!-- ░░ Preflight: still checking ░░ -->
    <div v-if="uiState === 'loading'"
         style="display:flex; align-items:center; gap:.6rem; color:rgba(10,12,27,.6); font-size:.9rem; padding:2rem 0;">
      <i class="pi pi-spinner pw-spin"></i> Checking plugin status…
    </div>

    <!-- ░░ Check failed: REST API unreachable → clean error page, no config ░░ -->
    <div v-else-if="uiState === 'unreachable'"
         style="border:1px solid #f6aea9; background:#fdf3f2; border-radius:8px; padding:1.5rem 1.75rem;">
      <div style="display:flex; align-items:center; gap:.5rem; margin-bottom:.6rem;">
        <i class="pi pi-times-circle" style="color:#c5221f; font-size:1.1rem;"></i>
        <strong style="font-size:1.05rem; color:#7a1c17;">Can't reach the plugin's REST API</strong>
      </div>
      <p style="margin:0 0 .75rem; font-size:.875rem; color:rgba(10,12,27,.75);">
        This screen can't load because its backend
        (<code>/opennms/rest/prometheus-remotewrite</code>) did not respond — so we won't pretend the
        configuration is usable. This usually means the plugin bundle isn't fully started.
      </p>
      <p style="margin:0 0 1rem; font-size:.8125rem; color:rgba(10,12,27,.6); font-family:monospace; background:rgba(10,12,27,.04); padding:.5rem .7rem; border-radius:4px;">{{ healthError }}</p>
      <p style="margin:0 0 1rem; font-size:.875rem; color:rgba(10,12,27,.75);">
        <strong>How to fix:</strong> confirm the <code>opennms-plugins-prometheus-remotewrite</code> feature is
        installed and its bundle is Active (Karaf: <code>feature:list -i | grep prometheus</code>), then re-check.
      </p>
      <button class="se-btn se-btn-secondary" :disabled="checkingHealth" @click="loadHealth">
        <i :class="checkingHealth ? 'pi pi-spinner pw-spin' : 'pi pi-refresh'"></i> Re-check
      </button>
    </div>

    <!-- ░░ Check failed: prerequisites not met → instruction page, config is LOCKED ░░ -->
    <div v-else-if="uiState === 'blocked'"
         style="border:1px solid #f5e0a2; background:#fef9e7; border-radius:8px; padding:1.5rem 1.75rem;">
      <div style="display:flex; align-items:center; gap:.5rem; margin-bottom:.6rem;">
        <i class="pi pi-exclamation-triangle" style="color:#9a7400; font-size:1.1rem;"></i>
        <strong style="font-size:1.05rem; color:#7a6000;">This plugin isn't active yet</strong>
      </div>
      <p style="margin:0 0 1rem; font-size:.875rem; color:#7a6000;">
        Configuration is locked until the checks below pass — there's no point editing settings the plugin
        can't use yet. Current OpenNMS time-series strategy:
        <code>{{ health?.timeseriesStrategy }}</code>
        <span style="opacity:.75;">(from {{ health?.strategySource }})</span>.
      </p>
      <div v-for="(iss, i) in (health?.issues || [])" :key="i"
           :style="{
             borderLeft: '4px solid ' + (iss.severity === 'error' ? '#c5221f' : '#9a7400'),
             padding: '.6rem .9rem', marginTop: '.6rem', background: 'rgba(255,255,255,.6)', borderRadius: '0 6px 6px 0',
           }">
        <div style="font-weight:600; font-size:.875rem; color:rgba(10,12,27,.85);">{{ iss.title }}</div>
        <div style="font-size:.8125rem; color:rgba(10,12,27,.7); margin-top:.25rem;">{{ iss.detail }}</div>
        <div style="font-size:.8125rem; color:rgba(10,12,27,.85); margin-top:.4rem;">
          <strong>How to fix:</strong> {{ iss.remedy }}
        </div>
      </div>
      <button class="se-btn se-btn-secondary" :disabled="checkingHealth" @click="loadHealth" style="margin-top:1rem;">
        <i :class="checkingHealth ? 'pi pi-spinner pw-spin' : 'pi pi-refresh'"></i> Re-check
      </button>

      <!-- Backend readiness — verify the target backend BEFORE flipping to integration -->
      <div style="margin-top:1.5rem; padding-top:1.1rem; border-top:1px solid rgba(154,116,0,.3);">
        <div style="font-weight:600; font-size:.875rem; color:#7a6000; margin-bottom:.35rem;">
          Verify your backend now
        </div>
        <p style="margin:0 0 .7rem; font-size:.8125rem; color:rgba(10,12,27,.7);">
          You run the backend yourself — any Prometheus <code>remote_write</code>-compatible store
          (<strong>Cortex, Mimir, Thanos, or VictoriaMetrics</strong>) — and this plugin writes to it. Point the
          URLs below at your running backend, confirm it's reachable and accepting metrics, then save. If a check
          fails, fix the endpoint here and re-test; you don't need to enable integration first.
        </p>

        <!-- Editable connection endpoints (the rest of the config stays locked until integration is on) -->
        <div style="margin-bottom:.6rem;">
          <label style="display:block; font-size:.75rem; font-weight:600; margin-bottom:.25rem; color:rgba(10,12,27,.8);">Write URL</label>
          <input v-model="form.writeUrl" class="se-input" :class="{ error: formErrors.writeUrl }"
                 placeholder="http://localhost:9009/api/prom/push" />
          <span v-if="formErrors.writeUrl" style="color:#b3261e; font-size:.72rem;">{{ formErrors.writeUrl }}</span>
        </div>
        <div style="margin-bottom:.6rem;">
          <label style="display:block; font-size:.75rem; font-weight:600; margin-bottom:.25rem; color:rgba(10,12,27,.8);">Read URL</label>
          <input v-model="form.readUrl" class="se-input" :class="{ error: formErrors.readUrl }"
                 placeholder="http://localhost:9009/prometheus/api/v1" />
          <span v-if="formErrors.readUrl" style="color:#b3261e; font-size:.72rem;">{{ formErrors.readUrl }}</span>
        </div>
        <div style="margin-bottom:.7rem;">
          <label style="display:block; font-size:.75rem; font-weight:600; margin-bottom:.25rem; color:rgba(10,12,27,.8);">Organization ID <span style="font-weight:400; opacity:.7;">(optional — X-Scope-OrgID)</span></label>
          <input v-model="form.organizationId" class="se-input" placeholder="(none)" />
        </div>

        <div style="display:flex; gap:.5rem; flex-wrap:wrap; align-items:center;">
          <button class="se-btn se-btn-secondary" :disabled="testing || testingWrite" @click="testConnection">
            <i :class="testing ? 'pi pi-spinner pw-spin' : 'pi pi-bolt'"></i>
            {{ testing ? 'Checking…' : 'Check backend readiness' }}
          </button>
          <button class="se-btn se-btn-secondary" :disabled="testing || testingWrite" @click="sendTestWrite"
                  title="Pushes one synthetic sample into the backend">
            <i :class="testingWrite ? 'pi pi-spinner pw-spin' : 'pi pi-send'"></i>
            {{ testingWrite ? 'Writing…' : 'Send test write' }}
          </button>
          <button class="se-btn se-btn-primary" :disabled="savingConfig" @click="saveConfig">
            <i :class="savingConfig ? 'pi pi-spinner pw-spin' : 'pi pi-save'"></i>
            {{ savingConfig ? 'Saving…' : 'Save endpoints' }}
          </button>
          <span style="font-size:.72rem; color:rgba(10,12,27,.5);">Tests use the values above (unsaved). “Send test write” pushes one real sample.</span>
        </div>

        <div v-if="testResults" style="margin-top:.75rem; display:flex; flex-direction:column; gap:.5rem;">
          <div v-for="r in testResults" :key="r.endpoint"
               :style="{ border: '1px solid ' + (r.reachable ? '#b7dfc3' : '#f6aea9'), background: r.reachable ? '#f3faf5' : '#fdf3f2', borderRadius:'4px', padding:'.5rem .75rem', fontSize:'.8125rem' }">
            <div style="display:flex; align-items:center; gap:.5rem;">
              <i :class="r.reachable ? 'pi pi-check-circle' : 'pi pi-times-circle'" :style="{ color: r.reachable ? '#137333' : '#c5221f' }"></i>
              <strong style="text-transform:capitalize;">{{ r.endpoint }} endpoint</strong>
              <span style="color:rgba(10,12,27,.5);">{{ r.durationMs }} ms</span>
            </div>
            <div style="margin-top:.15rem; color:rgba(10,12,27,.7);">{{ r.detail }}</div>
          </div>
        </div>
        <div v-if="writeTestResult"
             :style="{ marginTop:'.5rem', border: '1px solid ' + (writeTestResult.reachable ? '#b7dfc3' : '#f6aea9'), background: writeTestResult.reachable ? '#f3faf5' : '#fdf3f2', borderRadius:'4px', padding:'.5rem .75rem', fontSize:'.8125rem' }">
          <div style="display:flex; align-items:center; gap:.5rem;">
            <i :class="writeTestResult.reachable ? 'pi pi-check-circle' : 'pi pi-times-circle'" :style="{ color: writeTestResult.reachable ? '#137333' : '#c5221f' }"></i>
            <strong>{{ writeTestResult.reachable ? 'Backend accepted a test write' : 'Test write rejected' }}</strong>
            <span style="color:rgba(10,12,27,.5);">{{ writeTestResult.durationMs }} ms</span>
          </div>
          <div style="margin-top:.15rem; color:rgba(10,12,27,.7);">{{ writeTestResult.detail }}</div>
        </div>
      </div>

      <!-- Enable integration (or, if already enabled on disk, prompt for the restart + offer revert) -->
      <div style="margin-top:1.5rem; padding-top:1.1rem; border-top:1px solid rgba(154,116,0,.3);">
        <template v-if="health?.pendingRestart && health?.configuredStrategy === 'integration'">
          <div style="display:flex; align-items:center; gap:.5rem; margin-bottom:.4rem;">
            <i class="pi pi-check-circle" style="color:#137333;"></i>
            <strong style="font-size:.875rem; color:rgba(10,12,27,.85);">Integration is enabled on disk — restart OpenNMS to apply</strong>
          </div>
          <p style="margin:0 0 .5rem; font-size:.8125rem; color:rgba(10,12,27,.7);">
            Wrote <code>{{ managedFilePath }}</code>. OpenNMS is still running
            <code>{{ health?.runningStrategy }}</code> until you restart — nothing flows through this plugin yet.
            Run one of these to apply:
          </p>
          <pre style="margin:0 0 .7rem; padding:.6rem .75rem; background:#0d1117; color:#e6edf3; border-radius:4px; font-size:.72rem; overflow:auto; white-space:pre-wrap;">{{ restartCommands }}</pre>
          <button class="se-btn se-btn-secondary" :disabled="applyingIntegration" @click="openConfirm('disable')">
            <i class="pi pi-undo"></i> Revert (undo)
          </button>
        </template>
        <template v-else>
          <div style="font-weight:600; font-size:.875rem; color:#7a6000; margin-bottom:.35rem;">Enable integration</div>
          <p style="margin:0 0 .6rem; font-size:.8125rem; color:rgba(10,12,27,.7);">
            Switch OpenNMS to this plugin. This writes a dedicated, reversible file and
            <strong>requires an OpenNMS restart</strong>. It changes how <strong>all</strong> OpenNMS metrics are
            stored — read the confirmation carefully. Enabled only after a test write to the saved endpoints succeeds.
          </p>
          <button class="se-btn se-btn-primary" :disabled="applyingIntegration || !canEnableIntegration"
                  :title="canEnableIntegration ? '' : enableBlockedReason" @click="openConfirm('enable')">
            <i class="pi pi-bolt"></i> Enable integration…
          </button>
          <p v-if="!canEnableIntegration" style="margin:.5rem 0 0; font-size:.75rem; color:#9a7400; display:flex; align-items:flex-start; gap:.35rem;">
            <i class="pi pi-lock" style="margin-top:.1rem;"></i><span>{{ enableBlockedReason }}</span>
          </p>
        </template>
      </div>
    </div>

    <!-- ░░ All checks passed → the actual configuration UI ░░ -->
    <template v-else>
    <!-- Non-blocking warnings (plugin works, but something else will bite — e.g. wrong graph engine) -->
    <div v-if="health?.issues?.length"
         style="border:1px solid #f5e0a2; background:#fef9e7; border-radius:6px; padding:.75rem 1rem; margin-bottom:1.25rem;">
      <div v-for="(iss, i) in health!.issues" :key="i" :style="{ marginTop: i > 0 ? '.6rem' : '0' }">
        <div style="display:flex; align-items:center; gap:.4rem; font-weight:600; font-size:.8125rem; color:#7a6000;">
          <i class="pi pi-exclamation-triangle"></i> {{ iss.title }}
        </div>
        <div style="font-size:.8125rem; color:rgba(10,12,27,.7); margin-top:.2rem;">{{ iss.detail }}</div>
        <div style="font-size:.8125rem; color:rgba(10,12,27,.85); margin-top:.25rem;"><strong>How to fix:</strong> {{ iss.remedy }}</div>
      </div>
    </div>
    <!-- Tab bar -->
    <div style="display:flex; gap:.25rem; border-bottom:1px solid rgba(10,12,27,.12); margin-bottom:1.5rem;">
      <button v-for="t in [
                { id: 'settings', label: 'Settings' },
                { id: 'stats', label: 'Statistics' },
                { id: 'explorer', label: 'Metric Explorer' }]"
              :key="t.id"
              @click="activeTab = (t.id as any)"
              :style="{
                padding: '.625rem 1rem', cursor: 'pointer', background: 'transparent',
                border: 'none', borderBottom: activeTab === t.id ? '2px solid var(--feather-primary,#273180)' : '2px solid transparent',
                color: activeTab === t.id ? 'var(--feather-primary,#273180)' : 'var(--feather-secondary-text-on-surface,rgba(10,12,27,.6))',
                fontWeight: activeTab === t.id ? 600 : 500, fontSize: '.875rem', marginBottom: '-1px',
              }">
        {{ t.label }}
      </button>
    </div>

    <!-- ════════════════ SETTINGS ════════════════ -->
    <div v-show="activeTab === 'settings'">
      <div v-if="loadingConfig" style="color:rgba(10,12,27,.6); font-size:.875rem;">Loading configuration…</div>

      <template v-else>
        <!-- Connection -->
        <section style="margin-bottom:1.75rem;">
          <h2 style="font-size:1rem; font-weight:600; margin:0 0 .75rem; padding-bottom:.4rem; border-bottom:1px solid rgba(10,12,27,.08);">Connection</h2>

          <div style="margin-bottom:1rem;">
            <label style="display:block; font-size:.8125rem; font-weight:600; margin-bottom:.3rem;">
              Write URL
              <i class="pi pi-question-circle" style="cursor:pointer; margin-left:.25rem; color:rgba(10,12,27,.4);" @click="toggleHelp('writeUrl')"></i>
            </label>
            <p v-if="showHelp.writeUrl" style="font-size:.8125rem; color:rgba(10,12,27,.6); margin:.1rem 0 .4rem;">
              The Prometheus <code>remote_write</code> push endpoint (e.g. Cortex / Mimir / Thanos / VictoriaMetrics). Samples are POSTed here.
            </p>
            <input v-model="form.writeUrl" class="se-input" :class="{ error: formErrors.writeUrl }"
                   placeholder="http://localhost:9009/api/prom/push" />
            <span v-if="formErrors.writeUrl" style="color:#b3261e; font-size:.75rem;">{{ formErrors.writeUrl }}</span>
          </div>

          <div style="margin-bottom:1rem;">
            <label style="display:block; font-size:.8125rem; font-weight:600; margin-bottom:.3rem;">
              Read URL
              <i class="pi pi-question-circle" style="cursor:pointer; margin-left:.25rem; color:rgba(10,12,27,.4);" @click="toggleHelp('readUrl')"></i>
            </label>
            <p v-if="showHelp.readUrl" style="font-size:.8125rem; color:rgba(10,12,27,.6); margin:.1rem 0 .4rem;">
              The Prometheus query API base used for reads (<code>/series</code>, <code>/query_range</code>). Usually the querier endpoint.
            </p>
            <input v-model="form.readUrl" class="se-input" :class="{ error: formErrors.readUrl }"
                   placeholder="http://localhost:9009/prometheus/api/v1" />
            <span v-if="formErrors.readUrl" style="color:#b3261e; font-size:.75rem;">{{ formErrors.readUrl }}</span>
          </div>

          <div style="display:flex; gap:.75rem; align-items:center; margin-top:.75rem; flex-wrap:wrap;">
            <button class="se-btn se-btn-secondary" :disabled="testing || testingWrite" @click="testConnection">
              <i :class="testing ? 'pi pi-spinner pw-spin' : 'pi pi-bolt'"></i>
              {{ testing ? 'Testing…' : 'Test connection' }}
            </button>
            <button class="se-btn se-btn-secondary" :disabled="testing || testingWrite" @click="sendTestWrite"
                    title="Pushes one synthetic sample to confirm the backend accepts writes">
              <i :class="testingWrite ? 'pi pi-spinner pw-spin' : 'pi pi-send'"></i>
              {{ testingWrite ? 'Writing…' : 'Send test write' }}
            </button>
            <span style="font-size:.75rem; color:rgba(10,12,27,.5);">
              “Test connection” only probes reachability &amp; the read API. “Send test write” actually pushes one
              sample (<code>opennms_remotewrite_healthcheck</code>) into the backend.
            </span>
          </div>

          <div v-if="testResults" style="margin-top:1rem; display:flex; flex-direction:column; gap:.5rem;">
            <div v-for="r in testResults" :key="r.endpoint"
                 :style="{
                   border: '1px solid ' + (r.reachable ? '#b7dfc3' : '#f6aea9'),
                   background: r.reachable ? '#f3faf5' : '#fdf3f2',
                   borderRadius: '4px', padding: '.625rem .875rem', fontSize: '.8125rem',
                 }">
              <div style="display:flex; align-items:center; gap:.5rem;">
                <i :class="r.reachable ? 'pi pi-check-circle' : 'pi pi-times-circle'"
                   :style="{ color: r.reachable ? '#137333' : '#c5221f' }"></i>
                <strong style="text-transform:capitalize;">{{ r.endpoint }} endpoint</strong>
                <span style="color:rgba(10,12,27,.5);">{{ r.durationMs }} ms</span>
              </div>
              <div style="margin-top:.25rem; color:rgba(10,12,27,.7); word-break:break-all;">{{ r.url }}</div>
              <div style="margin-top:.15rem; color:rgba(10,12,27,.7);">{{ r.detail }}</div>
              <div v-if="r.endpoint === 'write' && r.reachable && r.statusCode >= 400" style="margin-top:.15rem; color:#7a6000;">
                A {{ r.statusCode }} from a write endpoint is normal for a GET — it expects POST, so reachability is
                confirmed. Use “Send test write” to verify it actually accepts metrics.
              </div>
            </div>
          </div>

          <!-- Test-write result (real push) -->
          <div v-if="writeTestResult" style="margin-top:.75rem;">
            <div :style="{
                   border: '1px solid ' + (writeTestResult.reachable ? '#b7dfc3' : '#f6aea9'),
                   background: writeTestResult.reachable ? '#f3faf5' : '#fdf3f2',
                   borderRadius: '4px', padding: '.625rem .875rem', fontSize: '.8125rem',
                 }">
              <div style="display:flex; align-items:center; gap:.5rem;">
                <i :class="writeTestResult.reachable ? 'pi pi-check-circle' : 'pi pi-times-circle'"
                   :style="{ color: writeTestResult.reachable ? '#137333' : '#c5221f' }"></i>
                <strong>{{ writeTestResult.reachable ? 'Backend accepted a test write' : 'Test write rejected' }}</strong>
                <span style="color:rgba(10,12,27,.5);">{{ writeTestResult.durationMs }} ms</span>
              </div>
              <div style="margin-top:.25rem; color:rgba(10,12,27,.7); word-break:break-all;">{{ writeTestResult.url }}</div>
              <div style="margin-top:.15rem; color:rgba(10,12,27,.7);">{{ writeTestResult.detail }}</div>
            </div>
          </div>
        </section>

        <!-- HTTP / Performance -->
        <section style="margin-bottom:1.75rem;">
          <h2 style="font-size:1rem; font-weight:600; margin:0 0 .75rem; padding-bottom:.4rem; border-bottom:1px solid rgba(10,12,27,.08);">HTTP &amp; Performance</h2>
          <div style="display:grid; grid-template-columns:1fr 1fr; gap:1rem;">
            <div>
              <label style="display:block; font-size:.8125rem; font-weight:600; margin-bottom:.3rem;">Max concurrent HTTP connections</label>
              <input v-model.number="form.maxConcurrentHttpConnections" type="number" min="1" class="se-input" />
            </div>
            <div></div>
            <div>
              <label style="display:block; font-size:.8125rem; font-weight:600; margin-bottom:.3rem;">Write timeout (ms)</label>
              <input v-model.number="form.writeTimeoutInMs" type="number" min="0" class="se-input" />
            </div>
            <div>
              <label style="display:block; font-size:.8125rem; font-weight:600; margin-bottom:.3rem;">Read timeout (ms)</label>
              <input v-model.number="form.readTimeoutInMs" type="number" min="0" class="se-input" />
            </div>
          </div>

          <div style="margin-top:1rem;">
            <label style="display:block; font-size:.8125rem; font-weight:600; margin-bottom:.3rem;">
              Bulkhead max wait
              <i class="pi pi-question-circle" style="cursor:pointer; margin-left:.25rem; color:rgba(10,12,27,.4);" @click="toggleHelp('bulkhead')"></i>
            </label>
            <p v-if="showHelp.bulkhead" style="font-size:.8125rem; color:rgba(10,12,27,.6); margin:.1rem 0 .4rem;">
              How long a write may wait for a free slot in the async HTTP bulkhead before being rejected.
              "Unlimited" (the default) means writes block until a slot frees up rather than being dropped.
            </p>
            <label style="display:inline-flex; align-items:center; gap:.4rem; font-size:.8125rem; margin-bottom:.4rem;">
              <input type="checkbox" v-model="form.bulkheadUnlimited" /> Unlimited (block until a slot is free)
            </label>
            <div v-if="!form.bulkheadUnlimited" style="display:flex; align-items:center; gap:.5rem; max-width:240px;">
              <input v-model.number="form.bulkheadMaxWaitDuration" type="number" min="0" class="se-input" />
              <span style="font-size:.8125rem; color:rgba(10,12,27,.6);">ms</span>
            </div>
          </div>
        </section>

        <!-- Caching -->
        <section style="margin-bottom:1.75rem;">
          <h2 style="font-size:1rem; font-weight:600; margin:0 0 .75rem; padding-bottom:.4rem; border-bottom:1px solid rgba(10,12,27,.08);">Caching</h2>
          <div style="display:grid; grid-template-columns:1fr 1fr; gap:1rem;">
            <div>
              <label style="display:block; font-size:.8125rem; font-weight:600; margin-bottom:.3rem;">
                Metric cache size
                <i class="pi pi-question-circle" style="cursor:pointer; margin-left:.25rem; color:rgba(10,12,27,.4);" @click="toggleHelp('metricCache')"></i>
              </label>
              <p v-if="showHelp.metricCache" style="font-size:.8125rem; color:rgba(10,12,27,.6); margin:.1rem 0 .4rem;">
                Number of metric definitions cached in memory to avoid re-fetching metadata on reads.
              </p>
              <input v-model.number="form.metricCacheSize" type="number" min="0" class="se-input" />
            </div>
            <div>
              <label style="display:block; font-size:.8125rem; font-weight:600; margin-bottom:.3rem;">
                External tags cache size
                <i class="pi pi-question-circle" style="cursor:pointer; margin-left:.25rem; color:rgba(10,12,27,.4);" @click="toggleHelp('extCache')"></i>
              </label>
              <p v-if="showHelp.extCache" style="font-size:.8125rem; color:rgba(10,12,27,.6); margin:.1rem 0 .4rem;">
                Number of external-tag sets cached to reduce key-value-store lookups when persisting metadata.
              </p>
              <input v-model.number="form.externalTagsCacheSize" type="number" min="0" class="se-input" />
            </div>
          </div>
        </section>

        <!-- Query -->
        <section style="margin-bottom:1.75rem;">
          <h2 style="font-size:1rem; font-weight:600; margin:0 0 .75rem; padding-bottom:.4rem; border-bottom:1px solid rgba(10,12,27,.08);">Query</h2>
          <div style="max-width:320px;">
            <label style="display:block; font-size:.8125rem; font-weight:600; margin-bottom:.3rem;">
              Max series lookback
              <i class="pi pi-question-circle" style="cursor:pointer; margin-left:.25rem; color:rgba(10,12,27,.4);" @click="toggleHelp('lookback')"></i>
            </label>
            <p v-if="showHelp.lookback" style="font-size:.8125rem; color:rgba(10,12,27,.6); margin:.1rem 0 .4rem;">
              How far back metric/series lookups search. Stored as seconds ({{ form.maxSeriesLookback.toLocaleString() }} s).
            </p>
            <div style="display:flex; align-items:center; gap:.5rem;">
              <input v-model.number="lookbackDays" type="number" min="0" class="se-input" @input="syncLookbackToSeconds" />
              <span style="font-size:.8125rem; color:rgba(10,12,27,.6);">days</span>
            </div>
          </div>
        </section>

        <!-- Multi-tenancy -->
        <section style="margin-bottom:1.5rem;">
          <h2 style="font-size:1rem; font-weight:600; margin:0 0 .75rem; padding-bottom:.4rem; border-bottom:1px solid rgba(10,12,27,.08);">Multi-tenancy</h2>
          <div style="max-width:420px;">
            <label style="display:block; font-size:.8125rem; font-weight:600; margin-bottom:.3rem;">
              Organization ID
              <i class="pi pi-question-circle" style="cursor:pointer; margin-left:.25rem; color:rgba(10,12,27,.4);" @click="toggleHelp('orgId')"></i>
            </label>
            <p v-if="showHelp.orgId" style="font-size:.8125rem; color:rgba(10,12,27,.6); margin:.1rem 0 .4rem;">
              Sent as the <code>X-Scope-OrgID</code> header on reads &amp; writes for multi-tenant backends like Cortex/Mimir. Leave blank for single-tenant.
            </p>
            <input v-model="form.organizationId" class="se-input" placeholder="(none)" />
          </div>
        </section>

        <!-- Save bar -->
        <div style="display:flex; gap:.75rem; padding-top:1rem; border-top:1px solid rgba(10,12,27,.1);">
          <button class="se-btn se-btn-primary" :disabled="savingConfig" @click="saveConfig">
            <i :class="savingConfig ? 'pi pi-spinner pw-spin' : 'pi pi-save'"></i>
            {{ savingConfig ? 'Saving…' : 'Save configuration' }}
          </button>
          <button class="se-btn se-btn-text" :disabled="loadingConfig" @click="loadConfig(); syncLookbackFromSeconds()">
            Reset
          </button>
        </div>

        <!-- Integration control — this plugin is the active store; allow reverting -->
        <section style="margin-top:2rem; padding:1rem 1.25rem; border:1px solid #f6d0a2; background:#fdf6ee; border-radius:6px;">
          <h2 style="font-size:.95rem; font-weight:600; margin:0 0 .4rem; color:#7a5000;">Time-series integration</h2>
          <p style="margin:0 0 .5rem; font-size:.8125rem; color:rgba(10,12,27,.7);">
            This plugin is currently the <strong>active OpenNMS time-series store</strong>
            (strategy <code>{{ health?.runningStrategy }}</code>). To stop using Prometheus, revert to the previous
            strategy. New metrics will go back to RRD; data already written to Prometheus stays in Prometheus and
            won't be merged back. <strong>Requires an OpenNMS restart</strong> after reverting:
          </p>
          <pre style="margin:0 0 .75rem; padding:.6rem .75rem; background:#0d1117; color:#e6edf3; border-radius:4px; font-size:.72rem; overflow:auto; white-space:pre-wrap;">{{ restartCommands }}</pre>
          <button class="se-btn se-btn-secondary" :disabled="applyingIntegration" @click="openConfirm('disable')">
            <i class="pi pi-undo"></i> Disable integration / revert…
          </button>
          <p v-if="health && !health.integrationManaged" style="margin:.6rem 0 0; font-size:.75rem; color:#9a7400;">
            Note: integration wasn't enabled via this plugin (no managed file), so it's set elsewhere
            (source: {{ health.strategySource }}). Revert will tell you where to remove it.
          </p>
        </section>
      </template>
    </div>

    <!-- ════════════════ STATISTICS ════════════════ -->
    <div v-show="activeTab === 'stats'">
      <div v-if="health && !health.storageServiceActive"
           style="border:1px solid #f6aea9; background:#fdf3f2; border-radius:6px; padding:1rem 1.25rem; font-size:.8125rem; color:#7a1c17;">
        <i class="pi pi-info-circle" style="margin-right:.4rem;"></i>
        Live statistics are unavailable because the Prometheus storage service isn't active in this OpenNMS
        (strategy is <code>{{ health.timeseriesStrategy }}</code>). See the banner above for how to enable it.
      </div>
      <template v-else>
      <div style="display:flex; gap:.75rem; align-items:center; margin-bottom:1.25rem;">
        <button class="se-btn se-btn-secondary" :disabled="loadingStats" @click="refreshStats">
          <i :class="loadingStats ? 'pi pi-spinner pw-spin' : 'pi pi-refresh'"></i> Refresh
        </button>
        <label style="display:inline-flex; align-items:center; gap:.4rem; font-size:.8125rem;">
          <input type="checkbox" :checked="autoRefresh" @change="toggleAutoRefresh" /> Auto-refresh (5s)
        </label>
      </div>

      <div v-if="!stats" style="display:flex; align-items:center; gap:.6rem; color:rgba(10,12,27,.6); font-size:.875rem;">
        <i class="pi pi-spinner pw-spin"></i> Loading live statistics…
      </div>

      <template v-else>
        <!-- Stored series — how many metrics are being collected, and by which nodes -->
        <section style="margin-bottom:1.75rem;">
          <h2 style="font-size:1rem; font-weight:600; margin:0 0 .25rem;">Stored series</h2>
          <p style="margin:0 0 .75rem; font-size:.8125rem; color:rgba(10,12,27,.6);">
            Distinct time series currently receiving samples (within Prometheus' ~5&nbsp;min staleness window).
            A node listed here <strong>is</strong> getting data written. Use the
            Metric Explorer's node filter to drill into its series.
          </p>
          <div v-if="loadingSeriesCount && !seriesCount" style="color:rgba(10,12,27,.6); font-size:.8125rem;">
            <i class="pi pi-spinner pw-spin"></i> Counting series…
          </div>
          <div v-else-if="!seriesCount" style="color:rgba(10,12,27,.6); font-size:.8125rem;">
            Series counts are unavailable (the read API did not answer the count query).
          </div>
          <template v-else>
            <div style="display:grid; grid-template-columns:repeat(auto-fill,minmax(200px,1fr)); gap:.75rem; margin-bottom:.75rem; max-width:720px;">
              <div style="border:1px solid rgba(10,12,27,.12); border-radius:6px; padding:.875rem 1rem;">
                <div style="font-size:1.5rem; font-weight:600; color:var(--feather-primary,#273180);">{{ seriesCount.totalSeries.toLocaleString() }}</div>
                <div style="font-size:.75rem; color:rgba(10,12,27,.6); margin-top:.2rem;">Active series (total)</div>
              </div>
              <div style="border:1px solid rgba(10,12,27,.12); border-radius:6px; padding:.875rem 1rem;">
                <div style="font-size:1.5rem; font-weight:600; color:var(--feather-primary,#273180);">{{ seriesCount.nodesWritingData.toLocaleString() }}</div>
                <div style="font-size:.75rem; color:rgba(10,12,27,.6); margin-top:.2rem;">Nodes writing data</div>
              </div>
              <div style="border:1px solid rgba(10,12,27,.12); border-radius:6px; padding:.875rem 1rem;">
                <div style="font-size:1.5rem; font-weight:600; color:var(--feather-primary,#273180);">{{ seriesCount.seriesWithoutNodeTag.toLocaleString() }}</div>
                <div style="font-size:.75rem; color:rgba(10,12,27,.6); margin-top:.2rem;">Series without a node tag</div>
              </div>
            </div>

            <!-- Server-side search + pagination: usable with 50k nodes -->
            <div style="max-width:560px;">
              <div style="position:relative; margin-bottom:.5rem;">
                <i class="pi pi-search" style="position:absolute; left:.6rem; top:50%; transform:translateY(-50%); color:rgba(10,12,27,.4); font-size:.8rem;"></i>
                <input v-model="nodeTableSearch" class="se-input" style="padding-left:2rem;"
                       placeholder="Search nodes…" autocomplete="off" spellcheck="false"
                       @input="onNodeTableSearch" />
              </div>
              <table class="se-table" style="width:100%; border-collapse:collapse; font-size:.8125rem;">
                <thead>
                  <tr style="text-align:left; border-bottom:2px solid rgba(10,12,27,.12);">
                    <th style="padding:.5rem .6rem;">Node</th>
                    <th style="padding:.5rem .6rem; text-align:right;">Active series</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="n in seriesCount.byNode" :key="n.node" style="border-bottom:1px solid rgba(10,12,27,.07);">
                    <td style="padding:.5rem .6rem;">{{ n.node }}</td>
                    <td style="padding:.5rem .6rem; text-align:right; font-variant-numeric:tabular-nums;">{{ n.series.toLocaleString() }}</td>
                  </tr>
                  <tr v-if="!seriesCount.byNode.length">
                    <td colspan="2" style="padding:.6rem; color:rgba(10,12,27,.55);">
                      No nodes match “{{ nodeTableSearch }}”.
                    </td>
                  </tr>
                </tbody>
              </table>
              <div v-if="seriesCount.matchingNodes > 0"
                   style="display:flex; align-items:center; justify-content:space-between; margin-top:.5rem; font-size:.8125rem; color:rgba(10,12,27,.6);">
                <span>
                  Showing {{ (seriesCount.offset + 1).toLocaleString() }}–{{ Math.min(seriesCount.offset + seriesCount.byNode.length, seriesCount.matchingNodes).toLocaleString() }}
                  of {{ seriesCount.matchingNodes.toLocaleString() }} nodes
                </span>
                <span style="display:flex; gap:.4rem;">
                  <button class="se-btn se-btn-secondary" style="height:1.9rem; font-size:.72rem;"
                          :disabled="seriesCount.offset === 0 || loadingSeriesCount" @click="nodeTablePage(-1)">
                    <i class="pi pi-chevron-left"></i> Prev
                  </button>
                  <button class="se-btn se-btn-secondary" style="height:1.9rem; font-size:.72rem;"
                          :disabled="seriesCount.offset + seriesCount.byNode.length >= seriesCount.matchingNodes || loadingSeriesCount"
                          @click="nodeTablePage(1)">
                    Next <i class="pi pi-chevron-right"></i>
                  </button>
                </span>
              </div>
            </div>
          </template>
        </section>
        <!-- Gauges -->
        <section style="margin-bottom:1.75rem;">
          <h2 style="font-size:1rem; font-weight:600; margin:0 0 .75rem;">HTTP client &amp; bulkhead</h2>
          <div style="display:grid; grid-template-columns:repeat(auto-fill,minmax(200px,1fr)); gap:.75rem;">
            <div v-for="g in stats.gauges" :key="g.name"
                 style="border:1px solid rgba(10,12,27,.12); border-radius:6px; padding:.875rem 1rem;">
              <div style="font-size:1.5rem; font-weight:600; color:var(--feather-primary,#273180);">{{ fmtNum(g.value) }}</div>
              <div style="font-size:.75rem; color:rgba(10,12,27,.6); margin-top:.2rem;">{{ gaugeLabel(g.name) }}</div>
            </div>
          </div>
        </section>

        <!-- Meters -->
        <section>
          <h2 style="font-size:1rem; font-weight:600; margin:0 0 .75rem;">Throughput</h2>
          <table class="se-table" style="width:100%; border-collapse:collapse; font-size:.8125rem;">
            <thead>
              <tr style="text-align:left; border-bottom:2px solid rgba(10,12,27,.12);">
                <th style="padding:.5rem .6rem;">Meter</th>
                <th style="padding:.5rem .6rem; text-align:right;">Total count</th>
                <th style="padding:.5rem .6rem; text-align:right;">Mean / s</th>
                <th style="padding:.5rem .6rem; text-align:right;">1 min / s</th>
                <th style="padding:.5rem .6rem; text-align:right;">5 min / s</th>
                <th style="padding:.5rem .6rem; text-align:right;">15 min / s</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="m in stats.meters" :key="m.name" style="border-bottom:1px solid rgba(10,12,27,.07);">
                <td style="padding:.5rem .6rem;">{{ meterLabel(m.name) }}</td>
                <td style="padding:.5rem .6rem; text-align:right; font-variant-numeric:tabular-nums;">{{ m.count.toLocaleString() }}</td>
                <td style="padding:.5rem .6rem; text-align:right; font-variant-numeric:tabular-nums;">{{ m.meanRate.toFixed(3) }}</td>
                <td style="padding:.5rem .6rem; text-align:right; font-variant-numeric:tabular-nums;">{{ m.oneMinuteRate.toFixed(3) }}</td>
                <td style="padding:.5rem .6rem; text-align:right; font-variant-numeric:tabular-nums;">{{ m.fiveMinuteRate.toFixed(3) }}</td>
                <td style="padding:.5rem .6rem; text-align:right; font-variant-numeric:tabular-nums;">{{ m.fifteenMinuteRate.toFixed(3) }}</td>
              </tr>
            </tbody>
          </table>
        </section>
      </template>
      </template>
    </div>

    <!-- ════════════════ METRIC EXPLORER ════════════════ -->
    <div v-show="activeTab === 'explorer'">
      <p style="font-size:.875rem; color:rgba(10,12,27,.6); margin:0 0 1rem;">
        Find stored series by matching on tags. Use <code>name</code> for the metric name.
        Matchers are combined with AND. Focus a field to get suggestions from your stored data.
        <i class="pi pi-question-circle" style="cursor:pointer; margin-left:.25rem; color:rgba(10,12,27,.4);"
           title="How do I use this?" @click="toggleHelp('explorer')"></i>
      </p>

      <div v-if="showHelp.explorer"
           style="border:1px solid rgba(39,49,128,.25); background:rgba(39,49,128,.04); border-radius:6px; padding:.9rem 1.1rem; margin:0 0 1rem; font-size:.8125rem; color:rgba(10,12,27,.75); line-height:1.55;">
        <div style="font-weight:600; margin-bottom:.35rem; color:rgba(10,12,27,.85);">How to explore your metrics</div>
        <ul style="margin:0 0 .6rem; padding-left:1.1rem;">
          <li>Every series is stored as a set of <strong>tags</strong> (key=value pairs). Each row below is one
            condition on a tag; a series must satisfy <strong>all</strong> rows (AND) to be listed.</li>
          <li>The <strong>Node</strong> dropdown is a shortcut for the <code>node</code> meta tag — pick a node to
            list exactly the series being written for it. Meta tags (<code>node</code>, <code>location</code>, …)
            are matchable like any other tag key.</li>
          <li><code>name</code> is the metric name (e.g. <code>CpuUser</code>). Other common keys:
            <code>node</code>, <code>location</code>, <code>resourceId</code>, plus any meta tags you configured.</li>
          <li>Click into the <em>key</em> or <em>value</em> field and pick from the suggestions — they are read live
            from your backend, so only things that actually exist are offered. Type to narrow the list;
            use ↑ ↓ and Enter to pick.</li>
          <li>Operators: <code>=</code> exact match, <code>!=</code> exclude,
            <code>=~</code> / <code>!~</code> regular expression. A regex must match the
            <strong>whole</strong> value — use <code>.*Cpu.*</code> to search for “contains Cpu”.</li>
          <li>Press <strong>Enter</strong> in any field to run the query. Click a result row to see all of its tags.</li>
        </ul>
        <button class="se-btn se-btn-secondary" style="height:1.9rem; font-size:.72rem;" @click="tryExample">
          <i class="pi pi-play"></i> Try an example: name =~ .*Cpu.*
        </button>
      </div>

      <!-- Node quick filter: the most common question is "is node X writing data?" -->
      <div style="display:flex; align-items:center; gap:.6rem; margin-bottom:1rem; padding:.6rem .8rem; border:1px solid rgba(39,49,128,.2); background:rgba(39,49,128,.03); border-radius:6px;">
        <label style="font-size:.8125rem; font-weight:600; white-space:nowrap;">
          <i class="pi pi-filter" style="margin-right:.3rem; color:var(--feather-primary,#273180);"></i>Node
        </label>
        <div style="position:relative; flex:0 1 300px;">
          <input v-model="nodeSearch" class="se-input" placeholder="(any node) — type to search"
                 autocomplete="off" spellcheck="false"
                 @focus="openNodeSuggest" @input="openNodeSuggest"
                 @blur="onNodeBlur" @keydown="nodeKeydown" />
          <ul v-if="nodeSuggestOpen && (nodeSuggestLoading || (nodeSuggest?.values?.length ?? 0) > 0)" class="se-suggest">
            <li v-if="nodeSuggestLoading" class="se-suggest-note">Searching nodes…</li>
            <li v-for="(n, i) in (nodeSuggest?.values ?? [])" :key="n" :class="{ active: i === nodeSuggestIndex }"
                @mousedown.prevent="pickNode(n)">{{ n }}</li>
            <li v-if="nodeSuggest && nodeSuggest.totalMatching > (nodeSuggest.values?.length ?? 0)" class="se-suggest-note">
              Showing {{ nodeSuggest.values.length }} of {{ nodeSuggest.totalMatching.toLocaleString() }} nodes — keep typing to narrow down
            </li>
          </ul>
        </div>
        <button v-if="appliedNodeFilter" class="se-btn-icon danger" title="Clear the node filter"
                @mousedown.prevent="clearNodeFilter">
          <i class="pi pi-times"></i>
        </button>
        <span style="font-size:.75rem; color:rgba(10,12,27,.55);">
          Searches the <code>node</code> meta tag live from the backend and runs the query — the fastest way to
          check whether a specific node is getting data written. Combine with the matcher rows below to narrow further.
        </span>
      </div>

      <div style="display:flex; flex-direction:column; gap:.5rem; margin-bottom:1rem;">
        <div v-for="m in matchers" :key="m.id" style="display:flex; gap:.5rem; align-items:center;">
          <div style="position:relative; flex:0 0 30%;">
            <input v-model="m.key" class="se-input" placeholder="tag key (e.g. name)"
                   autocomplete="off" spellcheck="false"
                   @focus="openSuggest(m, 'key')" @input="openSuggest(m, 'key')"
                   @blur="closeSuggest()" @keydown="suggestKeydown($event, m, 'key')" />
            <ul v-if="suggestOpenFor(m, 'key')" class="se-suggest">
              <li v-if="suggestLoading" class="se-suggest-note">Loading suggestions…</li>
              <li v-for="(s, i) in suggestions" :key="s" :class="{ active: i === suggest.index }"
                  @mousedown.prevent="pickSuggestion(s)">{{ s }}</li>
              <li v-if="suggestTotal > suggestions.length" class="se-suggest-note">
                Showing {{ suggestions.length }} of {{ suggestTotal.toLocaleString() }} — keep typing to narrow down
              </li>
            </ul>
          </div>
          <select v-model="m.type" class="se-input" style="flex:0 0 170px;">
            <option v-for="t in MATCHER_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
          </select>
          <div style="position:relative; flex:1;">
            <input v-model="m.value" class="se-input" placeholder="value"
                   autocomplete="off" spellcheck="false"
                   @focus="openSuggest(m, 'value')" @input="openSuggest(m, 'value')"
                   @blur="closeSuggest()" @keydown="suggestKeydown($event, m, 'value')" />
            <ul v-if="suggestOpenFor(m, 'value')" class="se-suggest">
              <li v-if="suggestLoading" class="se-suggest-note">Loading suggestions…</li>
              <li v-for="(s, i) in suggestions" :key="s" :class="{ active: i === suggest.index }"
                  @mousedown.prevent="pickSuggestion(s)">{{ s }}</li>
              <li v-if="suggestTotal > suggestions.length" class="se-suggest-note">
                Showing {{ suggestions.length }} of {{ suggestTotal.toLocaleString() }} — keep typing to narrow down
              </li>
            </ul>
          </div>
          <button class="se-btn-icon danger" title="Remove" @click="removeMatcher(m.id)">
            <i class="pi pi-trash"></i>
          </button>
        </div>
      </div>

      <div style="display:flex; gap:.75rem; align-items:center; margin-bottom:1.5rem;">
        <button class="se-btn se-btn-text" @click="addMatcher"><i class="pi pi-plus"></i> Add matcher</button>
        <button class="se-btn se-btn-primary" :disabled="querying" @click="runQuery">
          <i :class="querying ? 'pi pi-spinner pw-spin' : 'pi pi-search'"></i>
          {{ querying ? 'Querying…' : 'Run query' }}
        </button>
      </div>

      <div v-if="queryResults !== null">
        <div v-if="queryResults.length === 0" style="color:rgba(10,12,27,.6); font-size:.875rem;">
          No matching series found. If you expected results, check the spelling of key and value
          (suggestions only offer what is actually stored), or switch the operator to
          <code>=~ (regex)</code> with a pattern like <code>.*Cpu.*</code> for a broader search.
        </div>
        <template v-else>
          <div style="font-size:.8125rem; color:rgba(10,12,27,.6); margin-bottom:.5rem;">
            {{ queryResults.length }} series found
          </div>
          <table class="se-table" style="width:100%; border-collapse:collapse; font-size:.8125rem;">
            <thead>
              <tr style="text-align:left; border-bottom:2px solid rgba(10,12,27,.12);">
                <th style="padding:.5rem .6rem; width:2rem;"></th>
                <th style="padding:.5rem .6rem;">Metric</th>
                <th style="padding:.5rem .6rem;">Key</th>
                <th style="padding:.5rem .6rem; text-align:right;">Tags</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="m in queryResults" :key="m.key">
                <tr style="border-bottom:1px solid rgba(10,12,27,.07); cursor:pointer;" @click="toggleMetric(m.key)">
                  <td style="padding:.5rem .6rem;">
                    <i class="pi pi-chevron-right se-expansion-icon" :class="{ rotated: expandedMetric === m.key }"></i>
                  </td>
                  <td style="padding:.5rem .6rem; font-weight:600;">{{ m.name }}</td>
                  <td style="padding:.5rem .6rem; color:rgba(10,12,27,.6); font-family:monospace; word-break:break-all;">{{ m.key }}</td>
                  <td style="padding:.5rem .6rem; text-align:right; color:rgba(10,12,27,.6);">{{ tagCount(m) }}</td>
                </tr>
                <tr v-if="expandedMetric === m.key">
                  <td></td>
                  <td colspan="3" style="padding:.25rem .6rem 1rem;">
                    <div v-for="(group, gi) in [
                            { label: 'Intrinsic tags', tags: m.intrinsicTags },
                            { label: 'Meta tags', tags: m.metaTags },
                            { label: 'External tags', tags: m.externalTags }]"
                         :key="gi">
                      <div v-if="Object.keys(group.tags).length" style="margin-bottom:.5rem;">
                        <div style="font-size:.7rem; text-transform:uppercase; letter-spacing:.04em; color:rgba(10,12,27,.5); margin-bottom:.25rem;">{{ group.label }}</div>
                        <div style="display:flex; flex-wrap:wrap; gap:.35rem;">
                          <span v-for="(val, k) in group.tags" :key="k"
                                style="background:rgba(39,49,128,.08); border-radius:3px; padding:.15rem .45rem; font-family:monospace; font-size:.75rem;">
                            {{ k }}=<strong>{{ val }}</strong>
                          </span>
                        </div>
                      </div>
                    </div>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </template>
      </div>
    </div>
    </template>
    <!-- ░░ end ready-state configuration UI ░░ -->

    <!-- Confirmation dialog for enabling / reverting the system-wide integration strategy -->
    <Dialog v-model:visible="confirmVisible" modal :closable="!applyingIntegration"
            :header="confirmMode === 'enable' ? 'Enable integration strategy?' : 'Disable integration / revert?'"
            :style="{ width: '560px', maxWidth: '94vw' }">
      <div style="font-size:.8125rem; color:rgba(10,12,27,.85); line-height:1.5;">
        <div style="display:flex; align-items:flex-start; gap:.5rem; padding:.6rem .75rem; background:#fef9e7; border:1px solid #f5e0a2; border-radius:4px; margin-bottom:.9rem;">
          <i class="pi pi-exclamation-triangle" style="color:#9a7400; margin-top:.1rem;"></i>
          <div>
            <strong>This is a system-wide OpenNMS change and requires a restart.</strong>
            <ul style="margin:.4rem 0 0; padding-left:1.1rem;">
              <li>It changes how <strong>all</strong> OpenNMS metrics are stored — not just this plugin.</li>
              <li>Existing data isn't migrated: after enabling, new metrics go to Prometheus; after reverting, they go back to RRD. The two are <strong>not merged</strong>, so expect a gap in historical graphs around the switch.</li>
              <li>Collection/monitoring pauses briefly during the OpenNMS restart.</li>
              <li v-if="confirmMode === 'enable'">Make sure your Prometheus backend is running and the readiness check passes first.</li>
            </ul>
          </div>
        </div>

        <template v-if="confirmMode === 'enable'">
          <p style="margin:0 0 .3rem;"><strong>1.</strong> Creates this file:</p>
          <pre style="margin:0 0 .5rem; padding:.4rem .6rem; background:rgba(10,12,27,.05); border-radius:4px; font-size:.72rem; overflow:auto; white-space:pre-wrap;">{{ managedFilePath }}</pre>
          <p style="margin:0 0 .3rem;"><strong>2.</strong> With exactly these lines:</p>
          <pre style="margin:0 0 .7rem; padding:.6rem .75rem; background:rgba(10,12,27,.05); border-radius:4px; font-size:.72rem; overflow:auto; white-space:pre-wrap;">{{ MANAGED_FILE_CONTENT }}</pre>
          <p style="margin:0 0 .3rem;"><strong>3.</strong> Nothing changes until <u>you</u> restart OpenNMS — run one of:</p>
          <pre style="margin:0; padding:.6rem .75rem; background:#0d1117; color:#e6edf3; border-radius:4px; font-size:.72rem; overflow:auto; white-space:pre-wrap;">{{ restartCommands }}</pre>
          <p style="margin:.6rem 0 0; color:rgba(10,12,27,.6);">Revert any time by removing that file (or using “Disable / revert”).</p>
        </template>
        <template v-else>
          <p style="margin:0 0 .3rem;"><strong>1.</strong> Removes this file:</p>
          <pre style="margin:0 0 .7rem; padding:.4rem .6rem; background:rgba(10,12,27,.05); border-radius:4px; font-size:.72rem; overflow:auto; white-space:pre-wrap;">{{ managedFilePath }}</pre>
          <p style="margin:0 0 .3rem;"><strong>2.</strong> Then restart OpenNMS to revert to the previous strategy — run one of:</p>
          <pre style="margin:0; padding:.6rem .75rem; background:#0d1117; color:#e6edf3; border-radius:4px; font-size:.72rem; overflow:auto; white-space:pre-wrap;">{{ restartCommands }}</pre>
          <p style="margin:.6rem 0 0; color:rgba(10,12,27,.6);">If integration was also set in another file, you'll be told exactly where to remove it.</p>
        </template>
      </div>
      <template #footer>
        <button class="se-btn se-btn-text" :disabled="applyingIntegration" @click="confirmVisible = false">Cancel</button>
        <button class="se-btn se-btn-primary" :disabled="applyingIntegration" @click="runConfirm">
          <i :class="applyingIntegration ? 'pi pi-spinner pw-spin' : (confirmMode === 'enable' ? 'pi pi-bolt' : 'pi pi-undo')"></i>
          {{ applyingIntegration ? 'Working…' : (confirmMode === 'enable' ? 'Enable integration' : 'Disable / revert') }}
        </button>
      </template>
    </Dialog>

    <!-- Toasts (always available, for action feedback) -->
    <div style="position:fixed; top:1rem; right:1rem; display:flex; flex-direction:column; gap:.5rem; z-index:9999;">
      <div v-for="t in toasts" :key="t.id" :style="toastStyle(t.severity)">
        <strong>{{ t.summary }}</strong>
        <div>{{ t.detail }}</div>
      </div>
    </div>
  </div>
</template>
