<script lang="ts">
  export let intent: string = '';

  const INTENT_CONFIG: Record<string, { emoji: string; label: string; color: string }> = {
    RAG: { emoji: '📄', label: 'RAG', color: 'bg-blue-100 text-blue-700' },
    SQL: { emoji: '📊', label: 'SQL', color: 'bg-green-100 text-green-700' },
    HYBRID: { emoji: '🔀', label: 'Hybrid', color: 'bg-purple-100 text-purple-700' },
    URL_FETCH: { emoji: '🌐', label: 'URL', color: 'bg-orange-100 text-orange-700' },
    FILE: { emoji: '📎', label: 'File', color: 'bg-yellow-100 text-yellow-700' },
    IMAGE: { emoji: '🖼', label: 'Image', color: 'bg-pink-100 text-pink-700' },
  };

  $: config = INTENT_CONFIG[intent?.toUpperCase()] ?? null;
</script>

{#if config}
  <span class="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium {config.color}">
    {config.emoji} {config.label}
  </span>
{/if}
