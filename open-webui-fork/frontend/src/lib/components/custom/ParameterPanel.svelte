<script lang="ts">
  import { onMount } from 'svelte';
  import ParameterSlider from './ParameterSlider.svelte';
  import ParameterRadio from './ParameterRadio.svelte';

  // ──────────────────────────────────────────────
  // Props
  // ──────────────────────────────────────────────
  export let conversationId: string = '';
  export let userEmail: string = '';
  export let onParamsChange: (params: Record<string, unknown>) => void = () => {};

  // ──────────────────────────────────────────────
  // API Base
  // ──────────────────────────────────────────────
  const API_BASE =
    typeof window !== 'undefined' && window.location.hostname === 'localhost'
      ? 'http://localhost:8080'
      : '';

  // ──────────────────────────────────────────────
  // Types
  // ──────────────────────────────────────────────
  type LimitEntry = {
    minValue: number | null;
    maxValue: number | null;
    locked: boolean;
    lockedReason: string | null;
  };

  type Limits = Record<string, LimitEntry>;

  type Params = {
    top_k: number;
    similarity_threshold: number;
    temperature: number;
    top_p: number;
    max_tokens: number;
    sql_temperature: number;
    sql_few_shot_examples: number;
    query_timeout_sec: number;
    max_result_rows: number;
    force_path: string;
    hybrid_synthesis_style: string;
    max_history_turns: number;
    max_context_tokens: number;
  };

  // ──────────────────────────────────────────────
  // Static Defaults (fallback before API responds)
  // ──────────────────────────────────────────────
  const STATIC_DEFAULTS: Params = {
    top_k: 5,
    similarity_threshold: 0.65,
    temperature: 0.3,
    top_p: 0.9,
    max_tokens: 2000,
    sql_temperature: 0.0,
    sql_few_shot_examples: 3,
    query_timeout_sec: 30,
    max_result_rows: 100,
    force_path: 'AUTO',
    hybrid_synthesis_style: 'BALANCED',
    max_history_turns: 10,
    max_context_tokens: 4096,
  };

  // ──────────────────────────────────────────────
  // State
  // ──────────────────────────────────────────────
  let panelOpen: boolean = false;
  let params: Params = { ...STATIC_DEFAULTS };
  let limits: Limits = {};
  let loading = true;
  let saving = false;

  // Toast state
  let toastMessage = '';
  let toastType: 'success' | 'error' = 'success';
  let toastVisible = false;
  let toastTimer: ReturnType<typeof setTimeout>;

  // Accordion sections open state
  let sectionsOpen: Record<string, boolean> = {
    search: true,
    response: true,
    sql: false,
    routing: false,
    conversation: false,
  };

  // ──────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────
  function isLocked(key: keyof Params): boolean {
    return limits[key]?.locked ?? false;
  }

  function lockedReason(key: keyof Params): string {
    return limits[key]?.lockedReason ?? '';
  }

  function paramMin(key: keyof Params, fallback: number): number {
    return limits[key]?.minValue ?? fallback;
  }

  function paramMax(key: keyof Params, fallback: number): number {
    return limits[key]?.maxValue ?? fallback;
  }

  function showToast(msg: string, type: 'success' | 'error' = 'success') {
    clearTimeout(toastTimer);
    toastMessage = msg;
    toastType = type;
    toastVisible = true;
    toastTimer = setTimeout(() => { toastVisible = false; }, 4000);
  }

  function apiBuildHeaders(): HeadersInit {
    const h: Record<string, string> = { 'Content-Type': 'application/json' };
    if (userEmail) h['X-User-Email'] = userEmail;
    return h;
  }

  // ──────────────────────────────────────────────
  // Panel open/close (persisted)
  // ──────────────────────────────────────────────
  function togglePanel() {
    panelOpen = !panelOpen;
    try {
      localStorage.setItem('rag_param_panel_open', panelOpen ? '1' : '0');
    } catch (_) { /* ignore */ }
  }

  function toggleSection(key: string) {
    sectionsOpen = { ...sectionsOpen, [key]: !sectionsOpen[key] };
  }

  // ──────────────────────────────────────────────
  // Param updates (local only — not auto-saved)
  // ──────────────────────────────────────────────
  function updateParam<K extends keyof Params>(key: K, value: Params[K]) {
    params = { ...params, [key]: value };
    onParamsChange(params as unknown as Record<string, unknown>);
  }

  // ──────────────────────────────────────────────
  // API: Load profile
  // ──────────────────────────────────────────────
  async function loadProfile() {
    loading = true;
    try {
      const res = await fetch(`${API_BASE}/api/v1/user/param-profile`, {
        headers: apiBuildHeaders(),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = await res.json() as { params?: Partial<Params>; defaults?: Partial<Params>; limits?: Limits };
      limits = data.limits ?? {};
      // Merge server params over static defaults; server defaults as fallback
      const serverParams = data.params ?? data.defaults ?? {};
      params = { ...STATIC_DEFAULTS, ...serverParams } as Params;
      onParamsChange(params as unknown as Record<string, unknown>);
    } catch (err) {
      console.error('[ParameterPanel] loadProfile error:', err);
      // Keep static defaults — no toast noise on initial load
    } finally {
      loading = false;
    }
  }

  // ──────────────────────────────────────────────
  // API: Save profile
  // ──────────────────────────────────────────────
  async function saveProfile() {
    saving = true;
    try {
      const res = await fetch(`${API_BASE}/api/v1/user/param-profile`, {
        method: 'PUT',
        headers: apiBuildHeaders(),
        body: JSON.stringify({ params }),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      showToast('프로필에 저장됐습니다');
    } catch (err) {
      console.error('[ParameterPanel] saveProfile error:', err);
      showToast('저장에 실패했습니다', 'error');
    } finally {
      saving = false;
    }
  }

  // ──────────────────────────────────────────────
  // API: Save conversation override
  // ──────────────────────────────────────────────
  async function saveConversationOverride() {
    if (!conversationId) {
      showToast('대화를 먼저 시작해주세요', 'error');
      return;
    }
    saving = true;
    try {
      const res = await fetch(
        `${API_BASE}/api/v1/user/conversations/${conversationId}/param-override`,
        {
          method: 'PUT',
          headers: apiBuildHeaders(),
          body: JSON.stringify({ params }),
        }
      );
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      showToast('이 대화에 적용됩니다');
    } catch (err) {
      console.error('[ParameterPanel] saveConversationOverride error:', err);
      showToast('저장에 실패했습니다', 'error');
    } finally {
      saving = false;
    }
  }

  // ──────────────────────────────────────────────
  // API: Reset profile
  // ──────────────────────────────────────────────
  let showResetConfirm = false;

  async function confirmReset() {
    showResetConfirm = false;
    saving = true;
    try {
      const res = await fetch(`${API_BASE}/api/v1/user/param-profile`, {
        method: 'DELETE',
        headers: apiBuildHeaders(),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      await loadProfile();
      showToast('기본값으로 초기화됐습니다');
    } catch (err) {
      console.error('[ParameterPanel] resetProfile error:', err);
      showToast('초기화에 실패했습니다', 'error');
    } finally {
      saving = false;
    }
  }

  // ──────────────────────────────────────────────
  // Derived: hybrid_synthesis_style active?
  // ──────────────────────────────────────────────
  $: hybridStyleActive =
    params.force_path === 'AUTO' || params.force_path === 'FORCE_HYBRID';

  // ──────────────────────────────────────────────
  // Lifecycle
  // ──────────────────────────────────────────────
  onMount(() => {
    try {
      const stored = localStorage.getItem('rag_param_panel_open');
      if (stored !== null) panelOpen = stored === '1';
    } catch (_) { /* ignore */ }
    loadProfile();
  });
</script>

<!-- ════════════════════════════════════════════
     TOGGLE BUTTON (always visible)
════════════════════════════════════════════ -->
<div class="relative flex h-full">
  <!-- Narrow toggle tab -->
  <button
    type="button"
    class="flex flex-col items-center justify-center gap-1 w-8 h-full bg-white border-l border-gray-200
           hover:bg-gray-50 transition-colors flex-shrink-0 focus:outline-none focus:ring-1 focus:ring-blue-400"
    on:click={togglePanel}
    aria-label={panelOpen ? '파라미터 패널 접기' : '파라미터 패널 펼치기'}
    title={panelOpen ? '파라미터 패널 접기' : 'RAG 파라미터 설정'}
  >
    <svg
      xmlns="http://www.w3.org/2000/svg"
      class="w-4 h-4 text-gray-500"
      viewBox="0 0 20 20"
      fill="currentColor"
      aria-hidden="true"
    >
      <path d="M5 4a1 1 0 00-2 0v7.268a2 2 0 000 3.464V16a1 1 0 102 0v-1.268a2 2 0 000-3.464V4zM11 4a1 1 0 10-2 0v1.268a2 2 0 000 3.464V16a1 1 0 102 0V8.732a2 2 0 000-3.464V4zM16 3a1 1 0 011 1v7.268a2 2 0 010 3.464V16a1 1 0 11-2 0v-1.268a2 2 0 010-3.464V4a1 1 0 011-1z" />
    </svg>
    <span
      class="text-[9px] font-medium text-gray-400 writing-mode-vertical"
      style="writing-mode: vertical-rl; text-orientation: mixed; transform: rotate(180deg); letter-spacing: 0.05em;"
    >파라미터</span>
  </button>

  <!-- ════════════════════════════════════════════
       PANEL (slide in when open)
  ════════════════════════════════════════════ -->
  {#if panelOpen}
    <div
      class="w-72 h-full bg-white border-l border-gray-200 flex flex-col overflow-hidden shadow-lg"
      role="complementary"
      aria-label="RAG 파라미터 설정 패널"
    >
      <!-- Header -->
      <div class="flex-shrink-0 flex items-center justify-between px-3 py-2.5 border-b border-gray-100 bg-gray-50">
        <div class="flex items-center gap-1.5">
          <svg xmlns="http://www.w3.org/2000/svg" class="w-3.5 h-3.5 text-blue-500" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
            <path d="M5 4a1 1 0 00-2 0v7.268a2 2 0 000 3.464V16a1 1 0 102 0v-1.268a2 2 0 000-3.464V4zM11 4a1 1 0 10-2 0v1.268a2 2 0 000 3.464V16a1 1 0 102 0V8.732a2 2 0 000-3.464V4zM16 3a1 1 0 011 1v7.268a2 2 0 010 3.464V16a1 1 0 11-2 0v-1.268a2 2 0 010-3.464V4a1 1 0 011-1z" />
          </svg>
          <span class="text-xs font-semibold text-gray-700">RAG 파라미터</span>
          {#if loading}
            <span class="text-[10px] text-gray-400 animate-pulse">로딩중...</span>
          {/if}
        </div>

        <!-- Action buttons -->
        <div class="flex items-center gap-1">
          <!-- 프로필 저장 -->
          <button
            type="button"
            class="flex items-center gap-1 px-2 py-1 text-[10px] font-medium rounded
                   bg-blue-50 text-blue-600 border border-blue-200 hover:bg-blue-100
                   disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            disabled={saving || loading}
            on:click={saveProfile}
            title="프로필에 저장"
            aria-label="프로필 저장"
          >
            <svg xmlns="http://www.w3.org/2000/svg" class="w-3 h-3" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path d="M7.707 10.293a1 1 0 10-1.414 1.414l3 3a1 1 0 001.414 0l3-3a1 1 0 00-1.414-1.414L11 11.586V6h5a2 2 0 012 2v7a2 2 0 01-2 2H4a2 2 0 01-2-2V8a2 2 0 012-2h5v5.586l-1.293-1.293zM9 4a1 1 0 012 0v2H9V4z" />
            </svg>
            저장
          </button>

          <!-- 대화별 적용 -->
          <button
            type="button"
            class="flex items-center gap-1 px-2 py-1 text-[10px] font-medium rounded
                   bg-emerald-50 text-emerald-600 border border-emerald-200 hover:bg-emerald-100
                   disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            disabled={saving || loading || !conversationId}
            on:click={saveConversationOverride}
            title={conversationId ? '이 대화에만 적용' : '대화를 먼저 시작해주세요'}
            aria-label="이 대화에만 적용"
          >
            <svg xmlns="http://www.w3.org/2000/svg" class="w-3 h-3" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path fill-rule="evenodd" d="M18 10c0 3.866-3.582 7-8 7a8.841 8.841 0 01-4.083-.98L2 17l1.338-3.123C2.493 12.767 2 11.434 2 10c0-3.866 3.582-7 8-7s8 3.134 8 7zM7 9H5v2h2V9zm8 0h-2v2h2V9zM9 9h2v2H9V9z" clip-rule="evenodd" />
            </svg>
            대화별
          </button>

          <!-- 초기화 -->
          <button
            type="button"
            class="flex items-center gap-1 px-2 py-1 text-[10px] font-medium rounded
                   bg-gray-50 text-gray-500 border border-gray-200 hover:bg-gray-100
                   disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            disabled={saving || loading}
            on:click={() => { showResetConfirm = true; }}
            title="기본값으로 초기화"
            aria-label="기본값으로 초기화"
          >
            <svg xmlns="http://www.w3.org/2000/svg" class="w-3 h-3" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path fill-rule="evenodd" d="M4 2a1 1 0 011 1v2.101a7.002 7.002 0 0111.601 2.566 1 1 0 11-1.885.666A5.002 5.002 0 005.999 7H9a1 1 0 010 2H4a1 1 0 01-1-1V3a1 1 0 011-1zm.008 9.057a1 1 0 011.276.61A5.002 5.002 0 0014.001 13H11a1 1 0 110-2h5a1 1 0 011 1v5a1 1 0 11-2 0v-2.101a7.002 7.002 0 01-11.601-2.566 1 1 0 01.61-1.276z" clip-rule="evenodd" />
            </svg>
            초기화
          </button>
        </div>
      </div>

      <!-- Reset confirm inline banner -->
      {#if showResetConfirm}
        <div class="flex-shrink-0 flex items-center justify-between gap-2 px-3 py-2 bg-amber-50 border-b border-amber-200" role="alert">
          <span class="text-xs text-amber-700">프로필을 기본값으로 초기화할까요?</span>
          <div class="flex gap-1.5">
            <button
              type="button"
              class="text-xs px-2 py-0.5 rounded bg-amber-600 text-white hover:bg-amber-700"
              on:click={confirmReset}
            >확인</button>
            <button
              type="button"
              class="text-xs px-2 py-0.5 rounded bg-white border border-gray-300 text-gray-600 hover:bg-gray-50"
              on:click={() => { showResetConfirm = false; }}
            >취소</button>
          </div>
        </div>
      {/if}

      <!-- Scrollable param area -->
      <div class="flex-1 overflow-y-auto px-3 py-2 space-y-1">

        <!-- ── SECTION: 검색 설정 ── -->
        <section class="border border-gray-100 rounded-lg overflow-hidden">
          <button
            type="button"
            class="w-full flex items-center justify-between px-3 py-2 bg-gray-50 hover:bg-gray-100 transition-colors"
            on:click={() => toggleSection('search')}
            aria-expanded={sectionsOpen.search}
          >
            <span class="text-xs font-semibold text-gray-700">검색 설정</span>
            <svg
              xmlns="http://www.w3.org/2000/svg"
              class="w-3.5 h-3.5 text-gray-400 transition-transform {sectionsOpen.search ? '' : '-rotate-90'}"
              viewBox="0 0 20 20"
              fill="currentColor"
              aria-hidden="true"
            >
              <path fill-rule="evenodd" d="M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" clip-rule="evenodd" />
            </svg>
          </button>
          {#if sectionsOpen.search}
            <div class="px-3 py-3 space-y-4">
              <ParameterSlider
                label="Top-K (검색 문서 수)"
                tooltip="검색 시 반환할 최대 문서 수"
                min={paramMin('top_k', 1)}
                max={paramMax('top_k', 20)}
                step={1}
                value={params.top_k}
                locked={isLocked('top_k')}
                lockedReason={lockedReason('top_k')}
                onChange={(v) => updateParam('top_k', v)}
              />
              <ParameterSlider
                label="유사도 임계값"
                tooltip="이 값 미만의 유사도를 가진 문서는 제외됩니다"
                min={paramMin('similarity_threshold', 0.0)}
                max={paramMax('similarity_threshold', 1.0)}
                step={0.05}
                value={params.similarity_threshold}
                locked={isLocked('similarity_threshold')}
                lockedReason={lockedReason('similarity_threshold')}
                onChange={(v) => updateParam('similarity_threshold', v)}
              />
            </div>
          {/if}
        </section>

        <!-- ── SECTION: 응답 설정 ── -->
        <section class="border border-gray-100 rounded-lg overflow-hidden">
          <button
            type="button"
            class="w-full flex items-center justify-between px-3 py-2 bg-gray-50 hover:bg-gray-100 transition-colors"
            on:click={() => toggleSection('response')}
            aria-expanded={sectionsOpen.response}
          >
            <span class="text-xs font-semibold text-gray-700">응답 설정</span>
            <svg
              xmlns="http://www.w3.org/2000/svg"
              class="w-3.5 h-3.5 text-gray-400 transition-transform {sectionsOpen.response ? '' : '-rotate-90'}"
              viewBox="0 0 20 20"
              fill="currentColor"
              aria-hidden="true"
            >
              <path fill-rule="evenodd" d="M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" clip-rule="evenodd" />
            </svg>
          </button>
          {#if sectionsOpen.response}
            <div class="px-3 py-3 space-y-4">
              <ParameterSlider
                label="Temperature"
                tooltip="높을수록 창의적, 낮을수록 정확한 응답"
                min={paramMin('temperature', 0.0)}
                max={paramMax('temperature', 2.0)}
                step={0.1}
                value={params.temperature}
                locked={isLocked('temperature')}
                lockedReason={lockedReason('temperature')}
                onChange={(v) => updateParam('temperature', v)}
              />
              <ParameterSlider
                label="Top-P"
                tooltip="상위 확률 토큰만 선택하는 샘플링 기법. 낮을수록 일관된 응답, 높을수록 다양한 응답"
                min={paramMin('top_p', 0.0)}
                max={paramMax('top_p', 1.0)}
                step={0.05}
                value={params.top_p}
                locked={isLocked('top_p')}
                lockedReason={lockedReason('top_p')}
                onChange={(v) => updateParam('top_p', v)}
              />
              <ParameterSlider
                label="최대 토큰"
                tooltip="응답 최대 토큰 수"
                min={paramMin('max_tokens', 100)}
                max={paramMax('max_tokens', 4096)}
                step={100}
                value={params.max_tokens}
                locked={isLocked('max_tokens')}
                lockedReason={lockedReason('max_tokens')}
                onChange={(v) => updateParam('max_tokens', v)}
              />
            </div>
          {/if}
        </section>

        <!-- ── SECTION: SQL 설정 ── -->
        <section class="border border-gray-100 rounded-lg overflow-hidden">
          <button
            type="button"
            class="w-full flex items-center justify-between px-3 py-2 bg-gray-50 hover:bg-gray-100 transition-colors"
            on:click={() => toggleSection('sql')}
            aria-expanded={sectionsOpen.sql}
          >
            <span class="text-xs font-semibold text-gray-700">SQL 설정</span>
            <svg
              xmlns="http://www.w3.org/2000/svg"
              class="w-3.5 h-3.5 text-gray-400 transition-transform {sectionsOpen.sql ? '' : '-rotate-90'}"
              viewBox="0 0 20 20"
              fill="currentColor"
              aria-hidden="true"
            >
              <path fill-rule="evenodd" d="M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" clip-rule="evenodd" />
            </svg>
          </button>
          {#if sectionsOpen.sql}
            <div class="px-3 py-3 space-y-4">
              <ParameterSlider
                label="SQL Temperature"
                tooltip="SQL 생성 시 무작위성 정도. 정확성을 위해 시스템이 낮게 고정합니다."
                min={paramMin('sql_temperature', 0.0)}
                max={paramMax('sql_temperature', 1.0)}
                step={0.1}
                value={params.sql_temperature}
                locked={isLocked('sql_temperature')}
                lockedReason={lockedReason('sql_temperature')}
                onChange={(v) => updateParam('sql_temperature', v)}
              />
              <ParameterSlider
                label="SQL Few-shot 예시 수"
                tooltip="SQL 생성 시 참고할 예시 쿼리 수. 시스템이 고정합니다."
                min={paramMin('sql_few_shot_examples', 1)}
                max={paramMax('sql_few_shot_examples', 10)}
                step={1}
                value={params.sql_few_shot_examples}
                locked={isLocked('sql_few_shot_examples')}
                lockedReason={lockedReason('sql_few_shot_examples')}
                onChange={(v) => updateParam('sql_few_shot_examples', v)}
              />
              <ParameterSlider
                label="쿼리 타임아웃 (초)"
                tooltip="SQL 쿼리 실행 최대 대기 시간"
                min={paramMin('query_timeout_sec', 5)}
                max={paramMax('query_timeout_sec', 60)}
                step={1}
                value={params.query_timeout_sec}
                locked={isLocked('query_timeout_sec')}
                lockedReason={lockedReason('query_timeout_sec')}
                onChange={(v) => updateParam('query_timeout_sec', v)}
              />
              <ParameterSlider
                label="최대 결과 행 수"
                tooltip="SQL 결과로 반환할 최대 행 수"
                min={paramMin('max_result_rows', 10)}
                max={paramMax('max_result_rows', 10000)}
                step={10}
                value={params.max_result_rows}
                locked={isLocked('max_result_rows')}
                lockedReason={lockedReason('max_result_rows')}
                onChange={(v) => updateParam('max_result_rows', v)}
              />
            </div>
          {/if}
        </section>

        <!-- ── SECTION: 검색 방식 ── -->
        <section class="border border-gray-100 rounded-lg overflow-hidden">
          <button
            type="button"
            class="w-full flex items-center justify-between px-3 py-2 bg-gray-50 hover:bg-gray-100 transition-colors"
            on:click={() => toggleSection('routing')}
            aria-expanded={sectionsOpen.routing}
          >
            <span class="text-xs font-semibold text-gray-700">검색 방식</span>
            <svg
              xmlns="http://www.w3.org/2000/svg"
              class="w-3.5 h-3.5 text-gray-400 transition-transform {sectionsOpen.routing ? '' : '-rotate-90'}"
              viewBox="0 0 20 20"
              fill="currentColor"
              aria-hidden="true"
            >
              <path fill-rule="evenodd" d="M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" clip-rule="evenodd" />
            </svg>
          </button>
          {#if sectionsOpen.routing}
            <div class="px-3 py-3 space-y-4">
              <ParameterRadio
                label="검색 경로"
                value={params.force_path}
                options={[
                  { value: 'AUTO',         label: '자동',         description: '질문 유형에 따라 자동 선택' },
                  { value: 'FORCE_RAG',    label: 'RAG 강제',     description: '벡터 검색만 사용' },
                  { value: 'FORCE_SQL',    label: 'SQL 강제',     description: '자연어를 SQL로 변환해 데이터 조회' },
                  { value: 'FORCE_HYBRID', label: '하이브리드 강제', description: '벡터 검색과 SQL 조회를 함께 사용' },
                ]}
                disabled={isLocked('force_path')}
                onChange={(v) => updateParam('force_path', v)}
              />

              <!-- Conditional: hybrid style only active when AUTO or FORCE_HYBRID -->
              <div class="{hybridStyleActive ? '' : 'opacity-40 pointer-events-none'}">
                <ParameterRadio
                  label="하이브리드 합성 방식"
                  value={params.hybrid_synthesis_style}
                  options={[
                    { value: 'BALANCED',  label: '균형',      description: 'RAG와 SQL 결과를 동등하게 반영' },
                    { value: 'SQL_FIRST', label: 'SQL 우선',  description: 'SQL 결과를 주요 답변으로 사용' },
                    { value: 'RAG_FIRST', label: 'RAG 우선',  description: 'RAG 결과를 주요 답변으로 사용' },
                  ]}
                  disabled={!hybridStyleActive || isLocked('hybrid_synthesis_style')}
                  onChange={(v) => updateParam('hybrid_synthesis_style', v)}
                />
                {#if !hybridStyleActive}
                  <p class="text-[10px] text-gray-400 mt-1">검색 경로가 AUTO 또는 하이브리드일 때 활성화됩니다</p>
                {/if}
              </div>
            </div>
          {/if}
        </section>

        <!-- ── SECTION: 대화 설정 ── -->
        <section class="border border-gray-100 rounded-lg overflow-hidden">
          <button
            type="button"
            class="w-full flex items-center justify-between px-3 py-2 bg-gray-50 hover:bg-gray-100 transition-colors"
            on:click={() => toggleSection('conversation')}
            aria-expanded={sectionsOpen.conversation}
          >
            <span class="text-xs font-semibold text-gray-700">대화 설정</span>
            <svg
              xmlns="http://www.w3.org/2000/svg"
              class="w-3.5 h-3.5 text-gray-400 transition-transform {sectionsOpen.conversation ? '' : '-rotate-90'}"
              viewBox="0 0 20 20"
              fill="currentColor"
              aria-hidden="true"
            >
              <path fill-rule="evenodd" d="M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" clip-rule="evenodd" />
            </svg>
          </button>
          {#if sectionsOpen.conversation}
            <div class="px-3 py-3 space-y-4">
              <ParameterSlider
                label="대화 기록 턴 수"
                tooltip="컨텍스트로 포함할 이전 대화 수"
                min={paramMin('max_history_turns', 1)}
                max={paramMax('max_history_turns', 50)}
                step={1}
                value={params.max_history_turns}
                locked={isLocked('max_history_turns')}
                lockedReason={lockedReason('max_history_turns')}
                onChange={(v) => updateParam('max_history_turns', v)}
              />
              <ParameterSlider
                label="최대 컨텍스트 토큰"
                tooltip="LLM 컨텍스트 창 크기. 관리자 정책에 의해 고정됩니다."
                min={paramMin('max_context_tokens', 512)}
                max={paramMax('max_context_tokens', 32768)}
                step={512}
                value={params.max_context_tokens}
                locked={isLocked('max_context_tokens')}
                lockedReason={lockedReason('max_context_tokens')}
                onChange={(v) => updateParam('max_context_tokens', v)}
              />
            </div>
          {/if}
        </section>

      </div><!-- /scrollable -->
    </div>
  {/if}
</div>

<!-- ════════════════════════════════════════════
     TOAST
════════════════════════════════════════════ -->
{#if toastVisible}
  <div
    class="fixed bottom-4 right-4 z-50 flex items-center gap-3 px-4 py-3 rounded-lg shadow-lg border max-w-xs
           {toastType === 'success'
             ? 'bg-white border-gray-200 text-gray-700'
             : 'bg-red-50 border-red-200 text-red-700'}"
    role="status"
    aria-live="polite"
  >
    {#if toastType === 'success'}
      <svg xmlns="http://www.w3.org/2000/svg" class="w-4 h-4 text-emerald-500 flex-shrink-0" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
        <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" />
      </svg>
    {:else}
      <svg xmlns="http://www.w3.org/2000/svg" class="w-4 h-4 text-red-500 flex-shrink-0" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
        <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd" />
      </svg>
    {/if}
    <span class="text-sm flex-1">{toastMessage}</span>
    <button
      type="button"
      class="text-gray-400 hover:text-gray-600 text-lg leading-none"
      on:click={() => { toastVisible = false; }}
      aria-label="알림 닫기"
    >×</button>
  </div>
{/if}
