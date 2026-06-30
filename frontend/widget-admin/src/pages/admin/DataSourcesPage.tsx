import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Database,
  Plus,
  Trash2,
  RefreshCw,
  CheckCircle,
  XCircle,
  ChevronDown,
  ChevronUp,
  Play,
  Layers,
  TableProperties,
} from 'lucide-react'
import {
  listDataSources,
  createDataSource,
  deleteDataSource,
  testConnection,
  listTables,
  listRagTables,
  addRagTable,
  removeRagTable,
  triggerSync,
  listSyncJobs,
} from '../../api/admin/datasources'
import type {
  DataSource,
  DataSourceRequest,
  ConnectionTestResult,
  TableInfo,
  RagTable,
  SyncJob,
} from '../../api/admin/datasources'

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function errorMessage(err: unknown): string {
  if (err instanceof Error) return err.message
  return '알 수 없는 오류가 발생했습니다.'
}

const DEFAULT_FORM: DataSourceRequest = {
  name: '',
  description: '',
  dbType: 'mysql',
  host: '',
  port: 3306,
  dbName: '',
  username: '',
  password: '',
}

function statusBadge(status: SyncJob['status']) {
  const map: Record<SyncJob['status'], { label: string; cls: string }> = {
    pending: { label: '대기중', cls: 'bg-gray-100 text-gray-500 border-gray-200' },
    running: { label: '실행중', cls: 'bg-blue-50 text-blue-600 border-blue-200' },
    done: { label: '완료', cls: 'bg-green-50 text-green-700 border-green-200' },
    failed: { label: '실패', cls: 'bg-red-50 text-red-600 border-red-200' },
  }
  const { label, cls } = map[status]
  return (
    <span className={`text-xs font-medium px-2 py-0.5 rounded-full border ${cls}`}>{label}</span>
  )
}

interface TablePanelProps {
  ds: DataSource
}

function TablePanel({ ds }: TablePanelProps) {
  const qc = useQueryClient()
  const [syncingTable, setSyncingTable] = useState<string | null>(null)
  const [syncMsg, setSyncMsg] = useState<string | null>(null)

  const { data: tables = [], isLoading: tablesLoading } = useQuery({
    queryKey: ['ds-tables', ds.id],
    queryFn: () => listTables(ds.id),
  })

  const { data: ragTables = [], isLoading: ragLoading } = useQuery({
    queryKey: ['ds-rag-tables', ds.id],
    queryFn: () => listRagTables(ds.id),
  })

  const { data: syncJobs = [] } = useQuery({
    queryKey: ['ds-sync-jobs', ds.id],
    queryFn: () => listSyncJobs(ds.id),
  })

  const addRagMut = useMutation({
    mutationFn: (tableName: string) => addRagTable(ds.id, tableName),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ds-rag-tables', ds.id] }),
  })

  const removeRagMut = useMutation({
    mutationFn: (ragTableId: number) => removeRagTable(ds.id, ragTableId),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ds-rag-tables', ds.id] }),
  })

  const ragTableMap = new Map<string, RagTable>(ragTables.map(rt => [rt.tableName, rt]))

  const handleSync = async (tableName: string) => {
    setSyncingTable(tableName)
    setSyncMsg(null)
    try {
      await triggerSync(ds.id, tableName)
      setSyncMsg(`"${tableName}" 동기화가 시작되었습니다.`)
      qc.invalidateQueries({ queryKey: ['ds-sync-jobs', ds.id] })
    } catch (err) {
      setSyncMsg(`동기화 오류: ${errorMessage(err)}`)
    } finally {
      setSyncingTable(null)
    }
  }

  const recentJobsForTable = (tableName: string) =>
    syncJobs
      .filter(j => j.tableName === tableName)
      .sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime())
      .slice(0, 3)

  if (tablesLoading || ragLoading) {
    return <p className="text-sm text-gray-400 py-3">테이블 정보 불러오는 중…</p>
  }

  return (
    <div className="mt-3 border border-gray-200 rounded-lg overflow-hidden">
      {syncMsg && (
        <div className="bg-blue-50 border-b border-blue-200 px-4 py-2 text-xs text-blue-700">
          {syncMsg}
        </div>
      )}
      {tables.length === 0 ? (
        <div className="px-4 py-6 text-center text-sm text-gray-400">
          테이블이 없거나 연결에 실패했습니다.
        </div>
      ) : (
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-gray-50 border-b border-gray-200 text-xs text-gray-500">
              <th className="text-left px-4 py-2 font-medium">테이블명</th>
              <th className="text-left px-4 py-2 font-medium">설명</th>
              <th className="text-left px-4 py-2 font-medium">마지막 동기화</th>
              <th className="text-right px-4 py-2 font-medium">동작</th>
            </tr>
          </thead>
          <tbody>
            {tables.map((t: TableInfo) => {
              const ragTable = ragTableMap.get(t.tableName)
              const isRag = !!ragTable
              const recentJobs = recentJobsForTable(t.tableName)

              return (
                <>
                  <tr key={t.tableName} className="border-b border-gray-100 hover:bg-gray-50">
                    <td className="px-4 py-2 font-mono text-xs text-gray-800">{t.tableName}</td>
                    <td className="px-4 py-2 text-xs text-gray-500">{t.tableComment || '-'}</td>
                    <td className="px-4 py-2 text-xs text-gray-400">
                      {ragTable?.lastSyncedAt ? formatDate(ragTable.lastSyncedAt) : '-'}
                    </td>
                    <td className="px-4 py-2 text-right">
                      <div className="flex items-center justify-end gap-1.5">
                        {isRag ? (
                          <>
                            <button
                              onClick={() => handleSync(t.tableName)}
                              disabled={syncingTable === t.tableName}
                              className="flex items-center gap-1 text-xs text-blue-600 hover:text-blue-800 hover:bg-blue-50 px-2 py-1 rounded transition-colors disabled:opacity-50"
                            >
                              <Play size={11} />
                              동기화
                            </button>
                            <button
                              onClick={() => ragTable && removeRagMut.mutate(ragTable.id)}
                              disabled={removeRagMut.isPending}
                              className="flex items-center gap-1 text-xs text-gray-400 hover:text-red-600 hover:bg-red-50 px-2 py-1 rounded transition-colors disabled:opacity-50"
                            >
                              <Trash2 size={11} />
                              RAG 제거
                            </button>
                          </>
                        ) : (
                          <button
                            onClick={() => addRagMut.mutate(t.tableName)}
                            disabled={addRagMut.isPending}
                            className="flex items-center gap-1 text-xs text-green-600 hover:text-green-800 hover:bg-green-50 px-2 py-1 rounded transition-colors disabled:opacity-50"
                          >
                            <Plus size={11} />
                            RAG 추가
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                  {recentJobs.length > 0 && (
                    <tr key={`${t.tableName}-jobs`} className="bg-gray-50 border-b border-gray-100">
                      <td colSpan={4} className="px-6 py-2">
                        <div className="flex flex-wrap gap-3">
                          {recentJobs.map(job => (
                            <div key={job.id} className="flex items-center gap-2 text-xs text-gray-500">
                              {statusBadge(job.status)}
                              <span>{job.rowCount != null ? `${job.rowCount}행` : ''}</span>
                              <span>{formatDate(job.startedAt)}</span>
                              {job.errorMsg && (
                                <span className="text-red-500 truncate max-w-xs">{job.errorMsg}</span>
                              )}
                            </div>
                          ))}
                        </div>
                      </td>
                    </tr>
                  )}
                </>
              )
            })}
          </tbody>
        </table>
      )}
    </div>
  )
}

export default function DataSourcesPage() {
  const qc = useQueryClient()

  const [showModal, setShowModal] = useState(false)
  const [form, setForm] = useState<DataSourceRequest>(DEFAULT_FORM)
  const [formError, setFormError] = useState<string | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [expandedId, setExpandedId] = useState<number | null>(null)
  const [testResults, setTestResults] = useState<Record<number, ConnectionTestResult>>({})
  const [testingId, setTestingId] = useState<number | null>(null)

  const { data: dataSources = [], isLoading, error: listError } = useQuery({
    queryKey: ['admin-datasources'],
    queryFn: listDataSources,
  })

  const createMut = useMutation({
    mutationFn: (req: DataSourceRequest) => createDataSource(req),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-datasources'] })
      closeModal()
    },
    onError: (err: unknown) => setFormError(errorMessage(err)),
  })

  const deleteMut = useMutation({
    mutationFn: (id: number) => deleteDataSource(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-datasources'] }),
    onError: (err: unknown) => setErrorMsg(errorMessage(err)),
  })

  function openModal() {
    setForm(DEFAULT_FORM)
    setFormError(null)
    setShowModal(true)
  }

  function closeModal() {
    setShowModal(false)
    setForm(DEFAULT_FORM)
    setFormError(null)
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setFormError(null)
    createMut.mutate(form)
  }

  function handleDelete(ds: DataSource) {
    if (window.confirm(`"${ds.name}" 데이터소스를 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.`)) {
      deleteMut.mutate(ds.id)
    }
  }

  async function handleTest(ds: DataSource) {
    setTestingId(ds.id)
    try {
      const result = await testConnection(ds.id)
      setTestResults(prev => ({ ...prev, [ds.id]: result }))
    } catch (err) {
      setTestResults(prev => ({
        ...prev,
        [ds.id]: { connected: false, message: errorMessage(err) },
      }))
    } finally {
      setTestingId(null)
    }
  }

  function toggleExpand(id: number) {
    setExpandedId(prev => (prev === id ? null : id))
  }

  return (
    <div className="p-6">
      {/* 헤더 */}
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-2">
          <Database size={20} className="text-gray-500" />
          <h1 className="text-xl font-semibold text-gray-900">데이터소스</h1>
        </div>
        <button
          onClick={openModal}
          className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-3 py-2 rounded-lg text-sm font-medium transition-colors"
        >
          <Plus size={15} />
          데이터소스 추가
        </button>
      </div>

      {/* 인라인 에러 */}
      {errorMsg && (
        <div className="mb-4 bg-red-50 border border-red-200 text-red-600 text-sm px-4 py-2 rounded-lg flex items-center justify-between">
          <span>오류: {errorMsg}</span>
          <button
            onClick={() => setErrorMsg(null)}
            className="ml-4 text-red-400 hover:text-red-600 text-xs"
          >
            닫기
          </button>
        </div>
      )}

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {listError && <p className="text-sm text-red-500">오류: {String(listError)}</p>}

      {/* 목록 */}
      {!isLoading && (
        <>
          {dataSources.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-20 text-gray-400">
              <Database size={40} className="mb-3 opacity-30" />
              <p className="text-sm">등록된 데이터소스가 없습니다.</p>
              <p className="text-xs mt-1">위의 버튼으로 데이터소스를 추가하세요.</p>
            </div>
          ) : (
            <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
              <table className="w-full text-sm">
                <thead>
                  <tr className="bg-gray-50 border-b border-gray-200 text-xs text-gray-500">
                    <th className="text-left px-4 py-3 font-medium">이름</th>
                    <th className="text-left px-4 py-3 font-medium">DB 타입</th>
                    <th className="text-left px-4 py-3 font-medium">호스트:포트 / DB명</th>
                    <th className="text-left px-4 py-3 font-medium">상태</th>
                    <th className="text-right px-4 py-3 font-medium">동작</th>
                  </tr>
                </thead>
                <tbody>
                  {dataSources.map(ds => (
                    <>
                      <tr
                        key={ds.id}
                        className="border-b border-gray-100 hover:bg-gray-50 transition-colors"
                      >
                        <td className="px-4 py-3">
                          <div className="font-medium text-gray-900">{ds.name}</div>
                          {ds.description && (
                            <div className="text-xs text-gray-400 mt-0.5">{ds.description}</div>
                          )}
                        </td>
                        <td className="px-4 py-3">
                          <span className="font-mono text-xs bg-gray-100 text-gray-700 px-2 py-0.5 rounded">
                            {ds.dbType}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-xs text-gray-600 font-mono">
                          {ds.host}:{ds.port} / {ds.dbName}
                        </td>
                        <td className="px-4 py-3">
                          <span
                            className={`text-xs font-medium px-2 py-0.5 rounded-full border ${
                              ds.isActive
                                ? 'bg-green-50 text-green-700 border-green-200'
                                : 'bg-gray-100 text-gray-400 border-gray-200'
                            }`}
                          >
                            {ds.isActive ? '활성' : '비활성'}
                          </span>
                        </td>
                        <td className="px-4 py-3">
                          <div className="flex items-center justify-end gap-1.5">
                            {/* 연결 테스트 결과 인라인 표시 */}
                            {testResults[ds.id] && (
                              <span
                                className={`flex items-center gap-1 text-xs ${
                                  testResults[ds.id].connected ? 'text-green-600' : 'text-red-500'
                                }`}
                              >
                                {testResults[ds.id].connected ? (
                                  <CheckCircle size={12} />
                                ) : (
                                  <XCircle size={12} />
                                )}
                                {testResults[ds.id].message}
                              </span>
                            )}
                            <button
                              onClick={() => handleTest(ds)}
                              disabled={testingId === ds.id}
                              className="flex items-center gap-1 text-xs text-gray-500 hover:text-blue-600 hover:bg-blue-50 px-2 py-1 rounded transition-colors disabled:opacity-50"
                              title="연결 테스트"
                            >
                              <RefreshCw size={12} className={testingId === ds.id ? 'animate-spin' : ''} />
                              연결 테스트
                            </button>
                            <Link
                              to={`/admin/datasources/${ds.id}/rag-tables`}
                              className="flex items-center gap-1 text-xs text-gray-500 hover:text-blue-600 hover:bg-blue-50 px-2 py-1 rounded transition-colors"
                              title="RAG 테이블 관리"
                            >
                              <Layers size={12} />
                              RAG 테이블
                            </Link>
                            <Link
                              to={`/admin/datasources/${ds.id}/sql-tables`}
                              className="flex items-center gap-1 text-xs text-gray-500 hover:text-blue-600 hover:bg-blue-50 px-2 py-1 rounded transition-colors"
                              title="SQL 테이블 관리"
                            >
                              <TableProperties size={12} />
                              SQL 테이블
                            </Link>
                            <button
                              onClick={() => toggleExpand(ds.id)}
                              className="flex items-center gap-1 text-xs text-gray-500 hover:text-indigo-600 hover:bg-indigo-50 px-2 py-1 rounded transition-colors"
                              title="테이블 관리"
                            >
                              {expandedId === ds.id ? <ChevronUp size={12} /> : <ChevronDown size={12} />}
                              테이블 관리
                            </button>
                            <button
                              onClick={() => handleDelete(ds)}
                              disabled={deleteMut.isPending}
                              className="flex items-center gap-1 text-xs text-gray-400 hover:text-red-600 hover:bg-red-50 px-2 py-1 rounded transition-colors disabled:opacity-50"
                              title="삭제"
                            >
                              <Trash2 size={12} />
                              삭제
                            </button>
                          </div>
                        </td>
                      </tr>
                      {expandedId === ds.id && (
                        <tr key={`${ds.id}-panel`} className="bg-gray-50">
                          <td colSpan={5} className="px-6 py-4">
                            <TablePanel ds={ds} />
                          </td>
                        </tr>
                      )}
                    </>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      {/* 추가 모달 */}
      {showModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-xl shadow-xl w-full max-w-md mx-4 flex flex-col max-h-[90vh]">
            {/* 모달 헤더 */}
            <div className="px-6 py-4 border-b border-gray-200 flex items-center justify-between shrink-0">
              <h2 className="text-base font-semibold text-gray-900">데이터소스 추가</h2>
              <button
                onClick={closeModal}
                className="text-gray-400 hover:text-gray-600 transition-colors text-lg leading-none"
              >
                ×
              </button>
            </div>

            {/* 모달 본문 */}
            <form onSubmit={handleSubmit} className="flex flex-col flex-1 overflow-hidden">
              <div className="px-6 py-4 flex flex-col gap-4 flex-1 overflow-y-auto">
                {/* name */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">
                    이름 <span className="text-red-400">*</span>
                  </label>
                  <input
                    required
                    value={form.name}
                    onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                    placeholder="예: 운영 DB"
                    className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-full focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>

                {/* description */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">설명</label>
                  <input
                    value={form.description ?? ''}
                    onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                    placeholder="선택 입력"
                    className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-full focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>

                {/* dbType */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">
                    DB 타입 <span className="text-red-400">*</span>
                  </label>
                  <select
                    required
                    value={form.dbType}
                    onChange={e => setForm(f => ({ ...f, dbType: e.target.value }))}
                    className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-full focus:outline-none focus:ring-2 focus:ring-blue-500 bg-white"
                  >
                    <option value="mysql">MySQL</option>
                    <option value="mariadb">MariaDB</option>
                  </select>
                </div>

                {/* host */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">
                    호스트 <span className="text-red-400">*</span>
                  </label>
                  <input
                    required
                    value={form.host}
                    onChange={e => setForm(f => ({ ...f, host: e.target.value }))}
                    placeholder="예: 127.0.0.1"
                    className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-full focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>

                {/* port */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">
                    포트 <span className="text-red-400">*</span>
                  </label>
                  <input
                    required
                    type="number"
                    min={1}
                    max={65535}
                    value={form.port}
                    onChange={e => setForm(f => ({ ...f, port: Number(e.target.value) }))}
                    className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-full focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>

                {/* dbName */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">
                    데이터베이스명 <span className="text-red-400">*</span>
                  </label>
                  <input
                    required
                    value={form.dbName}
                    onChange={e => setForm(f => ({ ...f, dbName: e.target.value }))}
                    placeholder="예: mydb"
                    className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-full focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>

                {/* username */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">
                    사용자명 <span className="text-red-400">*</span>
                  </label>
                  <input
                    required
                    value={form.username}
                    onChange={e => setForm(f => ({ ...f, username: e.target.value }))}
                    placeholder="예: root"
                    className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-full focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>

                {/* password */}
                <div>
                  <label className="block text-xs font-medium text-gray-600 mb-1">
                    비밀번호 <span className="text-red-400">*</span>
                  </label>
                  <input
                    required
                    type="password"
                    value={form.password}
                    onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
                    placeholder="••••••••"
                    className="border border-gray-300 rounded-lg px-3 py-1.5 text-sm w-full focus:outline-none focus:ring-2 focus:ring-blue-500"
                  />
                </div>

                {formError && (
                  <p className="text-xs text-red-500">오류: {formError}</p>
                )}
              </div>

              {/* 모달 푸터 */}
              <div className="px-6 py-4 border-t border-gray-200 flex items-center justify-end gap-2 shrink-0">
                <button
                  type="button"
                  onClick={closeModal}
                  className="text-gray-500 hover:text-gray-700 px-4 py-1.5 text-sm transition-colors"
                >
                  취소
                </button>
                <button
                  type="submit"
                  disabled={createMut.isPending}
                  className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-1.5 rounded-lg text-sm font-medium transition-colors"
                >
                  {createMut.isPending ? '저장 중…' : '추가'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  )
}
