<script lang="ts">
  import { createEventDispatcher } from 'svelte';
  import AttachmentPreview from './AttachmentPreview.svelte';

  export let placeholder = '메시지를 입력하세요...';
  export let disabled = false;
  // RAG 파라미터 — ParameterPanel에서 주입, submit 이벤트에 포함됨 (ADR-0005)
  export let ragParams: Record<string, unknown> = {};

  const dispatch = createEventDispatcher<{
    submit: { message: string; attachments: Attachment[]; ragParams: Record<string, unknown> };
    attachmentsChange: Attachment[];
  }>();

  type Attachment = {
    id: string;
    name: string;
    size: number;
    type: string;
    previewUrl?: string;
    file: File;
  };

  let message = '';
  let attachments: Attachment[] = [];
  let fileInput: HTMLInputElement;

  // requirements/10: 지원 파일 타입
  const ACCEPTED_TYPES = [
    'image/*',
    'application/pdf',
    'text/plain',
    'text/csv',
    'application/vnd.ms-excel',
    'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    'application/msword',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  ].join(',');

  const MAX_TOTAL_SIZE = 100 * 1024 * 1024; // 100MB

  function openFilePicker() {
    fileInput?.click();
  }

  async function handleFileSelect(event: Event) {
    const input = event.target as HTMLInputElement;
    if (!input.files) return;

    for (const file of Array.from(input.files)) {
      const totalSize = attachments.reduce((s, a) => s + a.size, 0) + file.size;
      if (totalSize > MAX_TOTAL_SIZE) {
        alert('첨부파일 총 크기가 100MB를 초과합니다.');
        break;
      }

      const id = crypto.randomUUID();
      let previewUrl: string | undefined;
      if (file.type.startsWith('image/')) {
        previewUrl = URL.createObjectURL(file);
      }

      attachments = [...attachments, { id, name: file.name, size: file.size, type: file.type, previewUrl, file }];
    }

    dispatch('attachmentsChange', attachments);
    // 동일 파일 재선택 허용을 위해 input 초기화
    input.value = '';
  }

  function removeAttachment(id: string) {
    const att = attachments.find(a => a.id === id);
    if (att?.previewUrl) URL.revokeObjectURL(att.previewUrl);
    attachments = attachments.filter(a => a.id !== id);
    dispatch('attachmentsChange', attachments);
  }

  function handleSubmit() {
    if (!message.trim() && attachments.length === 0) return;
    // ragParams를 스냅샷으로 복사해 전송 시점 값을 보장
    dispatch('submit', { message: message.trim(), attachments, ragParams: { ...ragParams } });
    message = '';
    attachments.forEach(a => { if (a.previewUrl) URL.revokeObjectURL(a.previewUrl); });
    attachments = [];
  }

  function handleKeydown(event: KeyboardEvent) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      handleSubmit();
    }
  }
</script>

<div class="flex flex-col gap-2">
  <!-- 첨부파일 미리보기 -->
  <AttachmentPreview {attachments} onRemove={removeAttachment} />

  <!-- 입력 영역 -->
  <div class="flex items-end gap-2 border border-gray-300 rounded-xl px-3 py-2 bg-white focus-within:border-blue-400 transition-colors">
    <!-- 클립 버튼 (파일·이미지 통합) -->
    <button
      type="button"
      class="text-gray-400 hover:text-gray-600 transition-colors flex-shrink-0 mb-1"
      on:click={openFilePicker}
      title="파일 또는 이미지 첨부"
      {disabled}
    >
      📎
    </button>

    <!-- 숨김 파일 입력 -->
    <input
      bind:this={fileInput}
      type="file"
      accept={ACCEPTED_TYPES}
      multiple
      class="hidden"
      on:change={handleFileSelect}
    />

    <!-- 텍스트 입력 -->
    <textarea
      bind:value={message}
      {placeholder}
      {disabled}
      rows="1"
      class="flex-1 resize-none outline-none text-sm text-gray-800 bg-transparent max-h-48 overflow-y-auto"
      on:keydown={handleKeydown}
    />

    <!-- 전송 버튼 -->
    <button
      type="button"
      class="flex-shrink-0 mb-1 px-3 py-1 bg-blue-500 text-white text-sm rounded-lg
             hover:bg-blue-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
      disabled={disabled || (!message.trim() && attachments.length === 0)}
      on:click={handleSubmit}
    >전송</button>
  </div>
</div>
