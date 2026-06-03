<script lang="ts">
  // ADR-0005: 파라미터 7단계 우선순위 — UI 조작은 5단계 "사용자 세션 파라미터"
  // requirements/09-user-parameter-tuning.md 기준 13개 파라미터 (snake_case, 백엔드 API 연동)
  import ParameterPanel from '../../custom/ParameterPanel.svelte';

  export let conversationId: string = '';
  export let userEmail: string = '';
  export let onParamsChange: (params: Record<string, unknown>) => void = () => {};
</script>

<ParameterPanel {conversationId} {userEmail} {onParamsChange} />
