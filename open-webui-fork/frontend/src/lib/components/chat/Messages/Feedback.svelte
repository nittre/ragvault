<script lang="ts">
  export let messageId: string;
  export let onFeedback: (type: 'positive' | 'negative', reason?: string) => void = () => {};

  let showNegativeForm = false;
  let selectedReason = '';
  let submitted = false;

  const NEGATIVE_REASONS = ['정확하지 않음', '부족함', '무관함', '기타'];

  function handlePositive() {
    onFeedback('positive');
    submitted = true;
  }

  function handleNegative() {
    if (!showNegativeForm) {
      showNegativeForm = true;
      return;
    }
    if (selectedReason) {
      onFeedback('negative', selectedReason);
      submitted = true;
      showNegativeForm = false;
    }
  }
</script>

{#if !submitted}
  <div class="flex items-center gap-2 mt-1">
    <button
      class="text-gray-400 hover:text-green-500 transition-colors text-sm"
      on:click={handlePositive}
      title="도움이 됐어요"
    >👍</button>
    <button
      class="text-gray-400 hover:text-red-500 transition-colors text-sm"
      on:click={handleNegative}
      title="개선이 필요해요"
    >👎</button>
    {#if showNegativeForm}
      <div class="flex items-center gap-1 ml-2">
        {#each NEGATIVE_REASONS as reason}
          <button
            class="text-xs px-2 py-0.5 rounded-full border transition-colors
                   {selectedReason === reason
                     ? 'bg-red-100 border-red-400 text-red-700'
                     : 'border-gray-300 text-gray-500 hover:border-red-300'}"
            on:click={() => selectedReason = reason}
          >{reason}</button>
        {/each}
        <button
          class="text-xs px-2 py-0.5 bg-red-500 text-white rounded-full disabled:opacity-50"
          disabled={!selectedReason}
          on:click={handleNegative}
        >전송</button>
      </div>
    {/if}
  </div>
{:else}
  <span class="text-xs text-gray-400 mt-1">피드백 감사합니다 ✓</span>
{/if}
