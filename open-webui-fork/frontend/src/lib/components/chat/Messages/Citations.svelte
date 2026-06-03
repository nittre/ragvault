<script lang="ts">
  export let sources: Array<{
    id: string;
    title: string;
    content: string;
    score?: number;
    sourceType?: string;
  }> = [];
  export let expanded = false;

  // 점수 비노출 (ADR 결정)
  // [N] 인라인 인용 — 부모 컴포넌트에서 메시지 본문에 [1], [2] 치환 처리
</script>

{#if sources.length > 0}
  <div class="mt-3 border-t pt-2">
    <button
      class="text-xs text-gray-500 hover:text-gray-700 flex items-center gap-1"
      on:click={() => expanded = !expanded}
    >
      <span>{expanded ? '▲' : '▼'}</span>
      <span>출처 {sources.length}개</span>
    </button>
    {#if expanded}
      <div class="mt-2 space-y-2">
        {#each sources as src, i}
          <div class="bg-gray-50 rounded p-2 text-xs">
            <div class="font-medium text-gray-700">[{i + 1}] {src.title || '문서 ' + (i + 1)}</div>
            <div class="text-gray-500 mt-1 line-clamp-2">{src.content}</div>
          </div>
        {/each}
      </div>
    {/if}
  </div>
{/if}
