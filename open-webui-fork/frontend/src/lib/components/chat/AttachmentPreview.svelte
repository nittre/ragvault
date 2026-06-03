<script lang="ts">
  export let attachments: Array<{
    id: string;
    name: string;
    size: number;
    type: string;
    previewUrl?: string;
  }> = [];
  export let onRemove: (id: string) => void = () => {};

  const MAX_TOTAL = 100 * 1024 * 1024; // 100MB (requirements/10)

  $: totalSize = attachments.reduce((s, a) => s + a.size, 0);
  $: totalSizeMb = (totalSize / 1024 / 1024).toFixed(1);

  function isImage(type: string): boolean {
    return type.startsWith('image/');
  }

  function formatSize(bytes: number): string {
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + 'KB';
    return (bytes / 1024 / 1024).toFixed(1) + 'MB';
  }
</script>

{#if attachments.length > 0}
  <div class="flex flex-wrap gap-2 p-2 bg-gray-50 rounded-lg border border-gray-200">
    {#each attachments as att}
      <div class="relative flex items-center gap-2 bg-white border border-gray-200 rounded-lg px-2 py-1.5 text-xs shadow-sm">
        {#if isImage(att.type) && att.previewUrl}
          <img src={att.previewUrl} alt={att.name} class="w-8 h-8 object-cover rounded" />
        {:else}
          <span class="text-xl">
            {att.type.includes('pdf') ? '📄' : att.type.includes('image') ? '🖼' : '📎'}
          </span>
        {/if}
        <div>
          <div class="font-medium text-gray-700 max-w-[100px] truncate">{att.name}</div>
          <div class="text-gray-400">{formatSize(att.size)}</div>
        </div>
        <button
          class="absolute -top-1.5 -right-1.5 w-4 h-4 bg-gray-500 text-white rounded-full text-xs leading-none hover:bg-red-500"
          on:click={() => onRemove(att.id)}
        >×</button>
      </div>
    {/each}
    <div class="self-end text-xs text-gray-400 ml-auto">
      {totalSizeMb}MB / 100MB
      {#if totalSize > MAX_TOTAL}
        <span class="text-red-500 font-medium">초과!</span>
      {/if}
    </div>
  </div>
{/if}
