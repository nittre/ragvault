<script lang="ts">
  import { createEventDispatcher, onMount } from 'svelte';

  export let message = '모델 또는 파라미터가 변경되었습니다.';
  export let actionLabel = '이 답변 다시 받기';
  export let autoDismissMs = 6000;

  const dispatch = createEventDispatcher();
  let visible = true;
  let timer: ReturnType<typeof setTimeout>;

  onMount(() => {
    timer = setTimeout(() => { visible = false; }, autoDismissMs);
    return () => clearTimeout(timer);
  });

  function handleAction() {
    dispatch('action');
    visible = false;
  }

  function dismiss() {
    visible = false;
  }
</script>

{#if visible}
  <div class="fixed bottom-4 right-4 z-50 bg-white border border-gray-200 shadow-lg rounded-lg px-4 py-3 flex items-center gap-3 max-w-sm">
    <span class="text-sm text-gray-700 flex-1">{message}</span>
    <button
      class="text-sm font-medium text-blue-600 hover:text-blue-800 whitespace-nowrap"
      on:click={handleAction}
    >{actionLabel}</button>
    <button
      class="text-gray-400 hover:text-gray-600 text-lg leading-none"
      on:click={dismiss}
    >×</button>
  </div>
{/if}
