<script lang="ts">
  export let label: string;
  export let options: { value: string; label: string; description?: string }[];
  export let value: string;
  export let disabled: boolean = false;
  export let onChange: (v: string) => void = () => {};

  function handleChange(optionValue: string) {
    if (disabled) return;
    onChange(optionValue);
  }
</script>

<div class="flex flex-col gap-1.5">
  <span class="text-xs font-medium {disabled ? 'text-gray-400' : 'text-gray-700'}">
    {label}
  </span>

  <div class="flex flex-col gap-1">
    {#each options as opt (opt.value)}
      <button
        type="button"
        role="radio"
        aria-checked={value === opt.value}
        disabled={disabled}
        class="flex items-start gap-2 px-2.5 py-1.5 rounded-lg border text-left transition-colors
               {disabled
                 ? 'border-gray-200 bg-gray-50 cursor-not-allowed opacity-50'
                 : value === opt.value
                   ? 'border-blue-400 bg-blue-50 text-blue-700'
                   : 'border-gray-200 bg-white text-gray-700 hover:border-gray-300 hover:bg-gray-50 cursor-pointer'}"
        on:click={() => handleChange(opt.value)}
      >
        <!-- Custom radio dot -->
        <span
          class="mt-0.5 flex-shrink-0 w-3 h-3 rounded-full border flex items-center justify-center
                 {disabled
                   ? 'border-gray-300'
                   : value === opt.value
                     ? 'border-blue-500 bg-blue-500'
                     : 'border-gray-400'}"
          aria-hidden="true"
        >
          {#if value === opt.value && !disabled}
            <span class="w-1.5 h-1.5 rounded-full bg-white block"></span>
          {/if}
        </span>

        <div class="min-w-0">
          <span class="text-xs font-medium leading-tight">{opt.label}</span>
          {#if opt.description}
            <p class="text-[10px] text-gray-500 mt-0.5 leading-snug">{opt.description}</p>
          {/if}
        </div>
      </button>
    {/each}
  </div>
</div>
