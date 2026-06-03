<script lang="ts">
  export let label: string;
  export let tooltip: string = '';
  export let min: number;
  export let max: number;
  export let step: number = 1;
  export let value: number;
  export let locked: boolean = false;
  export let lockedReason: string = '';
  export let onChange: (v: number) => void = () => {};

  let showTooltip = false;

  function handleSlider(e: Event) {
    if (locked) return;
    const v = parseFloat((e.target as HTMLInputElement).value);
    onChange(v);
  }

  function handleInput(e: Event) {
    if (locked) return;
    const raw = parseFloat((e.target as HTMLInputElement).value);
    if (isNaN(raw)) return;
    const clamped = Math.min(max, Math.max(min, raw));
    onChange(clamped);
  }

  // Format display value: show integer if step >= 1, else fixed decimals
  $: displayStep = step >= 1 ? 0 : String(step).split('.')[1]?.length ?? 2;
  $: displayValue = typeof value === 'number' ? value.toFixed(displayStep) : '—';
</script>

<div class="flex flex-col gap-1">
  <!-- Label row -->
  <div class="flex items-center justify-between gap-1">
    <div class="flex items-center gap-1 min-w-0">
      <span class="text-xs font-medium {locked ? 'text-gray-400' : 'text-gray-700'} truncate">
        {label}
      </span>
      {#if locked}
        <!-- Lock icon with tooltip -->
        <div class="relative flex-shrink-0">
          <button
            type="button"
            class="text-gray-400 hover:text-gray-500 focus:outline-none"
            on:mouseenter={() => showTooltip = true}
            on:mouseleave={() => showTooltip = false}
            on:focus={() => showTooltip = true}
            on:blur={() => showTooltip = false}
            tabindex="0"
            aria-label="잠금: {lockedReason}"
          >
            <svg xmlns="http://www.w3.org/2000/svg" class="w-3 h-3" viewBox="0 0 20 20" fill="currentColor">
              <path fill-rule="evenodd" d="M5 9V7a5 5 0 0110 0v2a2 2 0 012 2v5a2 2 0 01-2 2H5a2 2 0 01-2-2v-5a2 2 0 012-2zm8-2v2H7V7a3 3 0 016 0z" clip-rule="evenodd" />
            </svg>
          </button>
          {#if showTooltip && lockedReason}
            <div
              class="absolute bottom-full left-1/2 -translate-x-1/2 mb-1 w-48 bg-gray-800 text-white text-xs rounded px-2 py-1 z-50 pointer-events-none whitespace-normal leading-snug"
              role="tooltip"
            >
              {lockedReason}
            </div>
          {/if}
        </div>
      {:else if tooltip}
        <div class="relative flex-shrink-0">
          <button
            type="button"
            class="text-gray-300 hover:text-gray-500 focus:outline-none"
            on:mouseenter={() => showTooltip = true}
            on:mouseleave={() => showTooltip = false}
            on:focus={() => showTooltip = true}
            on:blur={() => showTooltip = false}
            tabindex="0"
            aria-label={tooltip}
          >
            <svg xmlns="http://www.w3.org/2000/svg" class="w-3 h-3" viewBox="0 0 20 20" fill="currentColor">
              <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-8-3a1 1 0 00-.867.5 1 1 0 11-1.731-1A3 3 0 0113 8a3.001 3.001 0 01-2 2.83V11a1 1 0 11-2 0v-1a1 1 0 011-1 1 1 0 100-2zm0 8a1 1 0 100-2 1 1 0 000 2z" clip-rule="evenodd" />
            </svg>
          </button>
          {#if showTooltip}
            <div
              class="absolute bottom-full left-1/2 -translate-x-1/2 mb-1 w-48 bg-gray-800 text-white text-xs rounded px-2 py-1 z-50 pointer-events-none whitespace-normal leading-snug"
              role="tooltip"
            >
              {tooltip}
            </div>
          {/if}
        </div>
      {/if}
    </div>
    <!-- Numeric input -->
    <input
      type="number"
      {min}
      {max}
      {step}
      value={value}
      disabled={locked}
      class="w-16 text-xs text-right border rounded px-1 py-0.5
             {locked
               ? 'bg-gray-100 border-gray-200 text-gray-400 cursor-not-allowed'
               : 'border-gray-300 text-gray-800 focus:border-blue-400 focus:outline-none'}"
      on:change={handleInput}
      aria-label="{label} 값 입력"
    />
  </div>

  <!-- Slider -->
  <input
    type="range"
    {min}
    {max}
    {step}
    value={value}
    disabled={locked}
    class="w-full h-1.5 rounded-full appearance-none
           {locked
             ? 'accent-gray-300 cursor-not-allowed opacity-50'
             : 'accent-blue-500 cursor-pointer'}"
    on:input={handleSlider}
    aria-label="{label} 슬라이더"
    aria-valuemin={min}
    aria-valuemax={max}
    aria-valuenow={value}
  />

  <!-- Range hint -->
  <div class="flex justify-between text-[10px] text-gray-400 -mt-0.5">
    <span>{min}</span>
    <span>{max}</span>
  </div>
</div>
