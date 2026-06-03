<script lang="ts">
  export let type: 'fallback' | 'sse-disconnect' = 'fallback';
  export let statusPageUrl = '/status';
</script>

{#if type === 'sse-disconnect'}
  <div class="flex items-center gap-2 bg-yellow-50 border border-yellow-200 rounded px-3 py-2 text-sm text-yellow-800">
    <span>⚠️ 응답이 중단되었습니다.</span>
    <button
      class="underline hover:no-underline"
      on:click={() => window.location.reload()}
    >다시 시도</button>
  </div>
{:else}
  <div class="flex items-center gap-3 bg-red-50 border border-red-200 rounded px-3 py-2 text-sm text-red-800">
    <span>❌ 오류가 발생했습니다.</span>
    <button
      class="underline hover:no-underline"
      on:click={() => window.location.reload()}
    >다시 시도</button>
    <a href={statusPageUrl} target="_blank" class="underline hover:no-underline">상태 페이지</a>
  </div>
{/if}
