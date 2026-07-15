import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, RefreshCw, Trash2, ScanSearch, Zap, PlayCircle, FileText } from 'lucide-react'
import {
  getSqlTables,
  createSqlTable,
  deleteSqlTable,
  updateSqlTable,
  bulkDeleteSqlTables,
  refreshSchemaCache,
  bulkImportSqlTables,
  getColumnDescriptions,
  updateColumnDescriptions,
  type SqlTable,
  type ColumnDescription,
} from '../../api/admin/sqlTables'
import {
  getSyncMode,
  updateSyncMode,
  replaySyncMode,
  getSqlDriftStatus,
  type DriftEntry,
} from '../../api/admin/syncMode'
import { triggerSync } from '../../api/admin/sync'
import SchemaDiscoverModal from '../../components/admin/SchemaDiscoverModal'
import Tooltip from '../../components/common/Tooltip'

export default function SqlTablesPage() {
  const { dsId: dsIdStr } = useParams<{ dsId: string }>()
  const dsId = Number(dsIdStr)
  const qc = useQueryClient()

  const { data: tables = [], isLoading, error } = useQuery({
    queryKey: ['admin-sql-tables', dsId],
    queryFn: () => getSqlTables(dsId),
    enabled: !!dsId,
    refetchInterval: (query) => {
      const data = query.state.data as SqlTable[] | undefined
      return data?.some(t => t.llmStatus === 'pending') ? 3000 : false
    },
  })

  const { data: syncMode, refetch: refetchSyncMode } = useQuery({
    queryKey: ['sync-mode', dsId],
    queryFn: () => getSyncMode(dsId),
    enabled: !!dsId,
  })

  const sqlAutoSync = syncMode?.sql.autoSyncEnabled ?? false
  const sqlDisabledAt = syncMode?.sql.disabledAt ?? null

  const { data: driftData = [], refetch: refetchDrift, isFetching: driftFetching } = useQuery({
    queryKey: ['sql-drift', dsId],
    queryFn: () => getSqlDriftStatus(dsId),
    enabled: false, // 수동 호출만
  })

  const driftMap = new Map<string, DriftEntry>(driftData.map(d => [d.tableName, d]))

  const toggleSyncMut = useMutation({
    mutationFn: (enabled: boolean) => updateSyncMode(dsId, 'sql', enabled),
    onSuccess: () => refetchSyncMode(),
  })

  const replayMut = useMutation({
    mutationFn: () => replaySyncMode(dsId, 'sql'),
    onSuccess: (result) => {
      qc.invalidateQueries({ queryKey: ['admin-sql-tables', dsId] })
      setBulkResult(`자동 동기화 완료: ${result.applied}개 적용, ${result.skipped}개 건너뜀`)
    },
  })

  const handleToggleAutoSync = async () => {
    const next = !sqlAutoSync
    if (next && sqlDisabledAt) {
      // OFF → ON: replay confirm
      const ok = window.confirm(
        `비활성화 시점(${new Date(sqlDisabledAt).toLocaleString()})부터 현재까지의 변경사항을 즉시 자동 동기화하시겠습니까?`
      )
      if (!ok) return
      await toggleSyncMut.mutateAsync(true)
      replayMut.mutate()
    } else {
      await toggleSyncMut.mutateAsync(next)
    }
  }

  const createMut = useMutation({
    mutationFn: (body: Partial<SqlTable>) => createSqlTable(dsId, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-sql-tables', dsId] })
      setShowForm(false)
      setForm({ sourceTable: '', displayName: '' })
    },
  })

  const deleteMut = useMutation({
    mutationFn: (id: number) => deleteSqlTable(dsId, id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-sql-tables', dsId] }),
  })

  const updateMut = useMutation({
    mutationFn: ({ id, body }: { id: number; body: Parameters<typeof updateSqlTable>[2] }) =>
      updateSqlTable(dsId, id, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-sql-tables', dsId] }),
  })

  const refreshMut = useMutation({ mutationFn: () => refreshSchemaCache(dsId) })

  const syncNowMut = useMutation({
    mutationFn: async () => {
      const job = await triggerSync(dsId)
      const replay = await replaySyncMode(dsId, 'sql')
      return { job, replay }
    },
    onSuccess: ({ job, replay }) => {
      qc.invalidateQueries({ queryKey: ['admin-sql-tables', dsId] })
      if (job.status === 'failed') {
        setSyncError(job.errorMessage ?? '동기화 실패')
      } else {
        setBulkResult(
          `동기화 완료: 성공 ${job.recordsSuccess}건 / 실패 ${job.recordsFailed}건, ` +
          `화이트리스트 반영: ${replay.applied}개 적용 / ${replay.skipped}개 건너뜀`
        )
      }
    },
    onError: (err: unknown) => {
      const msg = err instanceof Error ? err.message : String(err)
      setSyncError(`동기화 요청 실패: ${msg}`)
    },
  })

  const handleSyncNow = () => {
    if (!sqlAutoSync) {
      const ok = window.confirm(
        '밀린 스키마 변경사항(컬럼 추가/삭제/이름변경)을 화이트리스트에 반영합니다. ' +
        '새로 추가된 컬럼은 검토 없이 즉시 text-to-SQL 조회 대상이 되며, ' +
        '이후 민감도 분석이 끝나도 자동으로 차단되지 않습니다. 계속할까요?'
      )
      if (!ok) return
    }
    syncNowMut.mutate()
  }

  // ── state ─────────────────────────────────────────────────────────────────
  const [showForm, setShowForm] = useState(false)
  const [showDiscover, setShowDiscover] = useState(false)
  const [form, setForm] = useState({ sourceTable: '', displayName: '' })
  const [importResult, setImportResult] = useState<{ imported: string[]; skipped: string[] } | null>(null)
  const [bulkResult, setBulkResult] = useState<string | null>(null)
  const [syncError, setSyncError] = useState<string | null>(null)

  // 테이블/컬럼 설명 편집 모달 상태
  const [editingDesc, setEditingDesc] = useState<{ id: number; sourceTable: string } | null>(null)
  const [descText, setDescText] = useState('')
  const [colDescs, setColDescs] = useState<ColumnDescription[]>([])
  const [colsLoading, setColsLoading] = useState(false)
  const [savingDesc, setSavingDesc] = useState(false)

  const openDescModal = async (t: { id: number; sourceTable: string; description: string | null }) => {
    setEditingDesc({ id: t.id, sourceTable: t.sourceTable })
    setDescText(t.description ?? '')
    setColDescs([])
    setColsLoading(true)
    try {
      setColDescs(await getColumnDescriptions(dsId, t.id))
    } finally {
      setColsLoading(false)
    }
  }

  const setColDesc = (name: string, description: string) =>
    setColDescs(prev => prev.map(c => (c.columnName === name ? { ...c, description } : c)))

  const saveDesc = async () => {
    if (!editingDesc) return
    setSavingDesc(true)
    try {
      await updateColumnDescriptions(
        dsId,
        editingDesc.id,
        colDescs.map(c => ({ columnName: c.columnName, description: c.description })),
      )
      await updateMut.mutateAsync({ id: editingDesc.id, body: { description: descText } })
      setEditingDesc(null)
    } finally {
      setSavingDesc(false)
    }
  }

  // ── checkbox selection ────────────────────────────────────────────────────
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const allSelected = tables.length > 0 && tables.every(t => selected.has(t.id))
  const someSelected = selected.size > 0

  const toggleRow = (id: number) =>
    setSelected(prev => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })

  const toggleAll = () =>
    setSelected(allSelected ? new Set() : new Set(tables.map(t => t.id)))

  // ── bulk delete ───────────────────────────────────────────────────────────
  const [bulkLoading, setBulkLoading] = useState(false)

  const handleBulkDelete = async () => {
    if (!window.confirm(`선택한 ${selected.size}개 테이블을 삭제하시겠습니까?`)) return
    setBulkLoading(true)
    try {
      const result = await bulkDeleteSqlTables(dsId, Array.from(selected))
      setSelected(new Set())
      qc.invalidateQueries({ queryKey: ['admin-sql-tables', dsId] })
      setBulkResult(`${result.succeeded.length}개 삭제 완료${result.failed.length > 0 ? ` (실패: ${result.failed.length}개)` : ''}`)
    } finally {
      setBulkLoading(false)
    }
  }

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault()
    createMut.mutate({
      sourceTable: form.sourceTable,
      displayName: form.displayName || form.sourceTable,
      dataSensitivity: 'internal',
    })
  }

  const handleBulkImport = async (tableNames: string[]) => {
    const result = await bulkImportSqlTables(dsId, tableNames)
    setImportResult(result)
    qc.invalidateQueries({ queryKey: ['admin-sql-tables', dsId] })
  }

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">SQL 테이블 화이트리스트</h1>
          <p className="text-sm text-gray-500 mt-0.5">Text-to-SQL 조회 허용 테이블을 관리합니다.</p>
        </div>
        <div className="flex gap-2 items-center">
          {/* 자동 동기화 모드 토글 */}
          <Tooltip side="bottom" text={sqlAutoSync ? '자동 동기화 ON — 클릭하면 비활성화' : '자동 동기화 OFF — 클릭하면 활성화'}>
            <button
              onClick={handleToggleAutoSync}
              disabled={toggleSyncMut.isPending}
              className={`flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium border transition-colors disabled:opacity-50 ${
                sqlAutoSync
                  ? 'bg-green-50 border-green-300 text-green-700 hover:bg-green-100'
                  : 'bg-gray-50 border-gray-300 text-gray-600 hover:bg-gray-100'
              }`}
            >
              <Zap size={14} className={sqlAutoSync ? 'text-green-600' : 'text-gray-400'} />
              {sqlAutoSync ? '자동 동기화 ON' : '자동 동기화 OFF'}
            </button>
          </Tooltip>
          {/* 드리프트 확인 (자동 동기화 OFF일 때) */}
          {!sqlAutoSync && (
            <Tooltip side="bottom" text="라이브 DB 스키마와 화이트리스트 설정을 비교해 테이블 삭제·컬럼 변경 여부를 확인합니다 (읽기 전용, 변경 없음)">
              <button
                onClick={() => refetchDrift()}
                disabled={driftFetching}
                className="flex items-center gap-2 border border-orange-300 hover:bg-orange-50 text-orange-600 px-3 py-2 rounded-lg text-sm font-medium disabled:opacity-50"
              >
                <RefreshCw size={14} className={driftFetching ? 'animate-spin' : ''} />
                드리프트 확인
              </button>
            </Tooltip>
          )}
          <Tooltip side="bottom" text="MySQL binlog를 즉시 동기화하고 밀린 DDL을 화이트리스트에 반영합니다">
            <button
              onClick={handleSyncNow}
              disabled={syncNowMut.isPending}
              className="flex items-center gap-2 border border-blue-300 hover:bg-blue-50 text-blue-700 px-3 py-2 rounded-lg text-sm font-medium disabled:opacity-50"
            >
              <PlayCircle size={14} className={syncNowMut.isPending ? 'animate-spin' : ''} />
              {syncNowMut.isPending ? '동기화 중...' : '지금 동기화'}
            </button>
          </Tooltip>
          <Tooltip side="bottom" text="text-to-SQL이 사용하는 컬럼 스키마 캐시를 강제로 새로고침합니다 (사용자 챗 전용, 어드민 화면 표시엔 영향 없음)">
            <button
              onClick={() => refreshMut.mutate()}
              disabled={refreshMut.isPending}
              className="flex items-center gap-2 border border-gray-300 hover:bg-gray-50 text-gray-700 px-3 py-2 rounded-lg text-sm font-medium disabled:opacity-50"
            >
              <RefreshCw size={14} className={refreshMut.isPending ? 'animate-spin' : ''} />
              캐시 갱신
            </button>
          </Tooltip>
          <Tooltip side="bottom" text="원본 DB의 전체 테이블을 조회해 화이트리스트에 없는 테이블을 일괄 등록합니다">
            <button
              onClick={() => setShowDiscover(true)}
              className="flex items-center gap-2 border border-gray-300 hover:bg-gray-50 text-gray-700 px-3 py-2 rounded-lg text-sm font-medium"
            >
              <ScanSearch size={15} />
              스키마 탐색
            </button>
          </Tooltip>
          <Tooltip side="bottom" align="end" text="테이블명을 직접 입력해 화이트리스트에 등록합니다">
            <button
              onClick={() => setShowForm(v => !v)}
              className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-3 py-2 rounded-lg text-sm font-medium"
            >
              <Plus size={15} /> 직접 추가
            </button>
          </Tooltip>
        </div>
      </div>

      {/* Notices */}
      {importResult && (
        <div className="mb-4 bg-blue-50 border border-blue-200 rounded-xl px-4 py-3 text-sm flex items-start justify-between gap-4">
          <div>
            <span className="font-medium text-blue-800">{importResult.imported.length}개 등록 완료</span>
            <span className="ml-2 text-blue-500 text-xs">LLM이 백그라운드에서 민감도를 분류 중입니다.</span>
            {importResult.skipped.length > 0 && (
              <span className="ml-2 text-orange-600">(건너뜀: {importResult.skipped.join(', ')})</span>
            )}
          </div>
          <button onClick={() => setImportResult(null)} className="text-blue-400 hover:text-blue-600 text-xs shrink-0">닫기</button>
        </div>
      )}
      {bulkResult && (
        <div className="mb-4 bg-gray-50 border border-gray-200 rounded-xl px-4 py-3 text-sm flex items-center justify-between">
          <span className="text-gray-700">{bulkResult}</span>
          <button onClick={() => setBulkResult(null)} className="text-gray-400 hover:text-gray-600 text-xs">닫기</button>
        </div>
      )}
      {syncError && (
        <div className="mb-4 bg-red-50 border border-red-200 rounded-xl px-4 py-3 text-sm flex items-start justify-between gap-4">
          <div>
            <span className="font-medium text-red-700">동기화 실패</span>
            <span className="ml-2 text-red-600">{syncError}</span>
          </div>
          <button onClick={() => setSyncError(null)} className="text-red-400 hover:text-red-600 text-xs shrink-0">닫기</button>
        </div>
      )}

      {showForm && (
        <form
          onSubmit={handleCreate}
          className="mb-6 bg-white border border-gray-200 rounded-xl p-4 flex flex-wrap gap-3 items-end"
        >
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">테이블명</label>
            <input
              required
              value={form.sourceTable}
              onChange={e => setForm(p => ({ ...p, sourceTable: e.target.value }))}
              placeholder="table_name"
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-48 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">표시명 (선택)</label>
            <input
              value={form.displayName}
              onChange={e => setForm(p => ({ ...p, displayName: e.target.value }))}
              placeholder="한글 표시명"
              className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-44 focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>
          <button
            type="submit"
            disabled={createMut.isPending}
            className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-1.5 rounded-lg text-sm font-medium"
          >
            {createMut.isPending ? '추가 중…' : '추가'}
          </button>
          <button type="button" onClick={() => setShowForm(false)} className="text-gray-500 px-3 py-1.5 text-sm">
            취소
          </button>
        </form>
      )}

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      {/* Bulk action bar */}
      {someSelected && (
        <div className="mb-3 flex items-center gap-3 bg-blue-50 border border-blue-200 rounded-xl px-4 py-2.5">
          <span className="text-sm font-medium text-blue-800">{selected.size}개 선택됨</span>
          <div className="flex gap-2 ml-auto">
            <Tooltip text="선택한 테이블을 한 번에 화이트리스트에서 삭제합니다">
              <button
                onClick={handleBulkDelete}
                disabled={bulkLoading}
                className="flex items-center gap-1.5 border border-red-300 text-red-600 hover:bg-red-50 px-3 py-1.5 rounded-lg text-xs font-medium disabled:opacity-50"
              >
                <Trash2 size={12} />
                {bulkLoading ? '삭제 중…' : '일괄 삭제'}
              </button>
            </Tooltip>
            <button
              onClick={() => setSelected(new Set())}
              className="text-gray-400 hover:text-gray-600 text-xs px-2"
            >
              선택 해제
            </button>
          </div>
        </div>
      )}

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr>
              <th className="px-4 py-3 w-8">
                <input
                  type="checkbox"
                  checked={allSelected}
                  onChange={toggleAll}
                  disabled={tables.length === 0}
                  className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                />
              </th>
              {['테이블명', '표시명', '설명', '민감도', '상태', '작업'].map(h => (
                <th key={h} className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wide">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {tables.map(t => {
              const isSelected = selected.has(t.id)
              const drift = driftMap.get(t.sourceTable)
              return (
                <tr key={t.id} className={`hover:bg-gray-50 ${isSelected ? 'bg-blue-50' : ''}`}>
                  <td className="px-4 py-3">
                    <input
                      type="checkbox"
                      checked={isSelected}
                      onChange={() => toggleRow(t.id)}
                      className="rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                    />
                  </td>
                  <td className="px-4 py-3 font-mono text-gray-900 text-xs">
                    <div className="flex items-center gap-1.5">
                      {t.sourceTable}
                      {drift?.status === 'table_missing' && (
                        <span className="inline-flex items-center gap-0.5 text-red-500 text-xs font-medium" title="소스 DB에서 테이블이 삭제됨">⚠ 테이블 없음</span>
                      )}
                      {drift?.status === 'column_mismatch' && (
                        <span className="inline-flex items-center gap-0.5 text-orange-500 text-xs font-medium" title={`삭제된 컬럼: ${drift.missingColumns.join(', ')}`}>
                          ⚠ 컬럼 변경
                        </span>
                      )}
                    </div>
                  </td>
                  <td className="px-4 py-3 text-gray-600 text-xs">{t.displayName || '-'}</td>
                  <td className="px-4 py-3 text-xs">
                    <Tooltip text={t.description ? t.description : '설명 없음 — 클릭하여 추가'}>
                      <button
                        onClick={() => openDescModal(t)}
                        className="flex items-center gap-1.5 text-xs px-2 py-1 rounded border transition-colors hover:bg-blue-50 hover:border-blue-300 hover:text-blue-700 border-gray-200 text-gray-400"
                      >
                        <FileText size={12} className={t.description ? 'text-blue-500' : ''} />
                        {t.description ? '설명 있음' : '추가'}
                      </button>
                    </Tooltip>
                  </td>
                  <td className="px-4 py-3 text-xs">
                    {t.llmStatus === 'pending' ? (
                      <span className="inline-flex items-center gap-1 text-blue-500 font-medium animate-pulse">⟳ 분류중</span>
                    ) : (
                      <select
                        value={t.dataSensitivity}
                        onChange={e => updateMut.mutate({ id: t.id, body: { dataSensitivity: e.target.value } })}
                        className={`text-xs border rounded px-1.5 py-0.5 font-medium focus:outline-none focus:ring-1 focus:ring-blue-400 ${
                          t.dataSensitivity === 'restricted'
                            ? 'bg-red-50 border-red-200 text-red-700'
                            : t.dataSensitivity === 'confidential'
                            ? 'bg-orange-50 border-orange-200 text-orange-700'
                            : t.dataSensitivity === 'public'
                            ? 'bg-blue-50 border-blue-200 text-blue-700'
                            : 'bg-gray-100 border-gray-200 text-gray-600'
                        }`}
                      >
                        <option value="public">public</option>
                        <option value="internal">internal</option>
                        <option value="confidential">confidential</option>
                        <option value="restricted">restricted</option>
                      </select>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <Tooltip text={t.isActive ? '클릭하면 text-to-SQL 조회 대상에서 제외합니다' : '클릭하면 text-to-SQL 조회 대상에 포함합니다'}>
                      <button
                        onClick={() => updateMut.mutate({ id: t.id, body: { isActive: !t.isActive } })}
                        className={`relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 focus:outline-none ${
                          t.isActive ? 'bg-green-500' : 'bg-gray-300'
                        }`}
                        role="switch"
                        aria-checked={t.isActive}
                      >
                        <span
                          className={`pointer-events-none inline-block h-4 w-4 rounded-full bg-white shadow transform transition-transform duration-200 ${
                            t.isActive ? 'translate-x-4' : 'translate-x-0'
                          }`}
                        />
                      </button>
                    </Tooltip>
                  </td>
                  <td className="px-4 py-3">
                    <Tooltip text="화이트리스트에서 이 테이블을 완전히 제거합니다">
                      <button
                        onClick={() => {
                          if (window.confirm(`${t.sourceTable}을 삭제하시겠습니까?`)) {
                            deleteMut.mutate(t.id)
                          }
                        }}
                        className="text-red-400 hover:text-red-600"
                      >
                        <Trash2 size={14} />
                      </button>
                    </Tooltip>
                  </td>
                </tr>
              )
            })}
            {tables.length === 0 && !isLoading && (
              <tr>
                <td colSpan={7} className="px-4 py-8 text-center text-gray-400 text-sm">
                  등록된 테이블이 없습니다. 스키마 탐색으로 일괄 등록하세요.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {showDiscover && (
        <SchemaDiscoverModal
          dsId={dsId}
          mode="sql"
          existingTableNames={tables.map(t => t.sourceTable)}
          onImport={handleBulkImport}
          onClose={() => setShowDiscover(false)}
        />
      )}

      {/* 테이블/컬럼 설명 편집 모달 */}
      {editingDesc && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-2xl mx-4 p-6 flex flex-col gap-4 max-h-[85vh]">
            <div>
              <h2 className="text-base font-semibold text-gray-900">테이블·컬럼 설명 편집</h2>
              <p className="text-xs text-gray-500 mt-0.5 font-mono">{editingDesc.sourceTable}</p>
            </div>
            <p className="text-xs text-blue-700 bg-blue-50 border border-blue-200 rounded-lg px-3 py-2 leading-relaxed">
              각 객체가 <strong>무엇인지</strong> 설명하세요. SQL 생성·데이터소스 라우팅 프롬프트에 주입됩니다.
              계산식·필터·여러 테이블 횡단 규칙은 <strong>백과사전</strong>에 작성하세요.
            </p>

            <div className="overflow-y-auto flex flex-col gap-4 pr-1">
              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1">테이블 설명</label>
                <textarea
                  value={descText}
                  onChange={e => setDescText(e.target.value)}
                  rows={3}
                  placeholder={"예: 수강생의 부트캠프 등록·수료 이력을 담는 테이블. learner_id로 learner와 연결된다."}
                  className="w-full border border-gray-300 rounded-xl px-3 py-2.5 text-sm resize-none focus:outline-none focus:ring-2 focus:ring-blue-400"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-gray-600 mb-1">컬럼 설명</label>
                {colsLoading ? (
                  <p className="text-xs text-gray-400 py-2">컬럼 불러오는 중…</p>
                ) : (
                  <div className="border border-gray-200 rounded-xl divide-y divide-gray-100">
                    {colDescs.map(c => (
                      <div key={c.columnName} className="flex items-center gap-2 px-3 py-2">
                        <div className="w-40 shrink-0">
                          <span className="font-mono text-xs text-gray-900">{c.columnName}</span>
                          <span className="block text-[10px] text-gray-400">
                            {c.dataType}{c.source ? ` · ${c.source}` : ''}
                          </span>
                        </div>
                        <input
                          value={c.description}
                          onChange={e => setColDesc(c.columnName, e.target.value)}
                          placeholder="이 컬럼의 의미"
                          className="flex-1 border border-gray-200 rounded-lg px-2.5 py-1.5 text-xs focus:outline-none focus:ring-1 focus:ring-blue-400"
                        />
                      </div>
                    ))}
                    {colDescs.length === 0 && (
                      <p className="text-xs text-gray-400 px-3 py-2">컬럼 정보가 없습니다.</p>
                    )}
                  </div>
                )}
              </div>
            </div>

            <div className="flex justify-end gap-2 pt-1">
              <button
                onClick={() => setEditingDesc(null)}
                className="px-4 py-2 text-sm text-gray-600 hover:text-gray-800 border border-gray-200 rounded-lg hover:bg-gray-50"
              >
                취소
              </button>
              <button
                onClick={saveDesc}
                disabled={savingDesc}
                className="px-4 py-2 text-sm font-medium text-white bg-blue-600 hover:bg-blue-700 disabled:opacity-50 rounded-lg"
              >
                {savingDesc ? '저장 중…' : '저장'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
