import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Database,
  Plus,
  Trash2,
  RefreshCw,
  CheckCircle,
  XCircle,
  Pencil,
  Eye,
  EyeOff,
  Terminal,
} from 'lucide-react'
import {
  listDataSources,
  createDataSource,
  updateDataSource,
  deleteDataSource,
  testConnection,
} from '../../api/admin/datasources'
import type {
  DataSource,
  DataSourceRequest,
  ConnectionTestResult,
} from '../../api/admin/datasources'

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
  sshEnabled: false,
  sshHost: '',
  sshPort: 22,
  sshUser: '',
  sshPrivateKey: '',
  sshPassphrase: '',
  autoDescribe: false,
}

export default function DataSourcesPage() {
  const qc = useQueryClient()

  const [showForm, setShowForm] = useState(false)
  const [editId, setEditId] = useState<number | null>(null)
  const [form, setForm] = useState<DataSourceRequest>(DEFAULT_FORM)
  const [formError, setFormError] = useState<string | null>(null)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [showPw, setShowPw] = useState(false)
  const [showPassphrase, setShowPassphrase] = useState(false)
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
      closeForm()
    },
    onError: (err: unknown) => setFormError(errorMessage(err)),
  })

  const updateMut = useMutation({
    mutationFn: ({ id, body }: { id: number; body: Partial<DataSourceRequest> }) =>
      updateDataSource(id, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['admin-datasources'] })
      closeForm()
    },
    onError: (err: unknown) => setFormError(errorMessage(err)),
  })

  const deleteMut = useMutation({
    mutationFn: (id: number) => deleteDataSource(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-datasources'] }),
    onError: (err: unknown) => setErrorMsg(errorMessage(err)),
  })

  const isSaving = createMut.isPending || updateMut.isPending

  function openCreate() {
    setEditId(null)
    setForm(DEFAULT_FORM)
    setFormError(null)
    setShowPw(false)
    setShowPassphrase(false)
    setShowForm(true)
  }

  function openEdit(ds: DataSource) {
    setEditId(ds.id)
    setForm({
      name: ds.name,
      description: ds.description ?? '',
      dbType: ds.dbType,
      host: ds.host,
      port: ds.port,
      dbName: ds.dbName,
      username: ds.username,
      password: '',
      sshEnabled: ds.sshEnabled,
      sshHost: ds.sshHost ?? '',
      sshPort: ds.sshPort ?? 22,
      sshUser: ds.sshUser ?? '',
      sshPrivateKey: '',    // 보안상 서버에서 내려오지 않음
      sshPassphrase: '',    // 보안상 서버에서 내려오지 않음
    })
    setFormError(null)
    setShowPw(false)
    setShowPassphrase(false)
    setShowForm(true)
  }

  function closeForm() {
    setShowForm(false)
    setEditId(null)
    setForm(DEFAULT_FORM)
    setFormError(null)
    setShowPw(false)
    setShowPassphrase(false)
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setFormError(null)
    const body: Partial<DataSourceRequest> = { ...form }
    if (!body.password) delete body.password
    if (!body.sshEnabled) {
      // SSH 비활성화 시 하위 필드만 제거 — sshEnabled 키 자체는 반드시 그대로 전송해야
      // 수정 시 "SSH 껐는데 안 꺼짐" 버그가 생기지 않는다.
      delete body.sshHost
      delete body.sshPort
      delete body.sshUser
      delete body.sshPrivateKey
      delete body.sshPassphrase
    } else {
      if (!body.sshPrivateKey) delete body.sshPrivateKey
      if (!body.sshPassphrase) delete body.sshPassphrase
    }
    if (editId !== null) {
      updateMut.mutate({ id: editId, body })
    } else {
      createMut.mutate(body as DataSourceRequest)
    }
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

  return (
    <div className="p-6">
      {/* 헤더 */}
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-2">
          <Database size={20} className="text-gray-500" />
          <h1 className="text-xl font-semibold text-gray-900">데이터소스</h1>
        </div>
        <button
          onClick={openCreate}
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

      {/* 등록/수정 인라인 폼 */}
      {showForm && (
        <div className="mb-6 bg-white border border-gray-200 rounded-xl p-5">
          <form onSubmit={handleSubmit} className="space-y-4">
            <h2 className="text-sm font-semibold text-gray-800">
              {editId !== null ? '데이터소스 수정' : '새 데이터소스 등록'}
            </h2>

            <div className="grid grid-cols-2 gap-4">
              <Field label="이름 *">
                <input
                  required
                  value={form.name}
                  onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                  placeholder="예: 운영 DB"
                  className={inputCls}
                />
              </Field>
              <Field label="DB 종류">
                <select
                  value={form.dbType}
                  onChange={e => setForm(f => ({ ...f, dbType: e.target.value }))}
                  className={inputCls}
                >
                  <option value="mysql">MySQL</option>
                  <option value="mariadb">MariaDB</option>
                </select>
              </Field>
            </div>

            <Field label="설명 (LLM 라우팅 힌트)">
              <input
                value={form.description ?? ''}
                onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                placeholder="이 DB에 어떤 데이터가 있는지 설명합니다. LLM이 DB 선택에 활용합니다."
                className={inputCls}
              />
            </Field>

            {editId === null && (
              <label className="flex items-start gap-2 cursor-pointer select-none bg-blue-50 border border-blue-200 rounded-lg px-3 py-2.5">
                <input
                  type="checkbox"
                  checked={!!form.autoDescribe}
                  onChange={e => setForm(f => ({ ...f, autoDescribe: e.target.checked }))}
                  className="w-4 h-4 mt-0.5 rounded accent-blue-600"
                />
                <span className="text-sm text-blue-800">
                  테이블·컬럼 설명 자동 생성
                  <span className="block text-xs text-blue-600 mt-0.5">
                    등록 후 LLM이 각 테이블·컬럼의 의미를 자동으로 채웁니다. (DB COMMENT가 있으면 우선 사용)
                  </span>
                </span>
              </label>
            )}

            <div className="grid grid-cols-3 gap-4">
              <div className="col-span-2">
                <Field label="DB 호스트 *">
                  <input
                    required
                    value={form.host}
                    onChange={e => setForm(f => ({ ...f, host: e.target.value }))}
                    placeholder="예: 127.0.0.1"
                    className={inputCls}
                  />
                </Field>
              </div>
              <Field label="포트">
                <input
                  type="number"
                  min={1}
                  max={65535}
                  value={form.port}
                  onChange={e => setForm(f => ({ ...f, port: Number(e.target.value) }))}
                  className={inputCls}
                />
              </Field>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <Field label="데이터베이스명 *">
                <input
                  required
                  value={form.dbName}
                  onChange={e => setForm(f => ({ ...f, dbName: e.target.value }))}
                  placeholder="예: mydb"
                  className={inputCls}
                />
              </Field>
              <Field label="사용자명 *">
                <input
                  required
                  value={form.username}
                  onChange={e => setForm(f => ({ ...f, username: e.target.value }))}
                  placeholder="예: root"
                  className={inputCls}
                />
              </Field>
            </div>

            <Field label={editId !== null ? '비밀번호 (변경 시만 입력)' : '비밀번호 *'}>
              <div className="relative">
                <input
                  required={editId === null}
                  type={showPw ? 'text' : 'password'}
                  value={form.password}
                  onChange={e => setForm(f => ({ ...f, password: e.target.value }))}
                  placeholder={editId !== null ? '변경하지 않으면 비워두세요' : '••••••••'}
                  className={inputCls + ' pr-9'}
                />
                <button
                  type="button"
                  onClick={() => setShowPw(v => !v)}
                  className="absolute right-2 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
                >
                  {showPw ? <EyeOff size={14} /> : <Eye size={14} />}
                </button>
              </div>
            </Field>

            {/* SSH 터널 섹션 */}
            <div className="border border-gray-200 rounded-lg p-4 space-y-3">
              <label className="flex items-center gap-2 cursor-pointer select-none">
                <input
                  type="checkbox"
                  checked={!!form.sshEnabled}
                  onChange={e => setForm(f => ({ ...f, sshEnabled: e.target.checked }))}
                  className="w-4 h-4 rounded accent-blue-600"
                />
                <Terminal size={14} className="text-gray-500" />
                <span className="text-sm font-medium text-gray-700">SSH 터널 사용</span>
                <span className="text-xs text-gray-400">— Bastion EC2 경유 + PEM 키 인증</span>
              </label>

              {form.sshEnabled && (
                <div className="space-y-3 pt-1 border-t border-gray-100">
                  <div className="grid grid-cols-3 gap-4">
                    <div className="col-span-2">
                      <Field label="Bastion 호스트 *">
                        <input
                          required={!!form.sshEnabled}
                          value={form.sshHost ?? ''}
                          onChange={e => setForm(f => ({ ...f, sshHost: e.target.value }))}
                          placeholder="3.36.255.123"
                          className={inputCls}
                        />
                      </Field>
                    </div>
                    <Field label="SSH 포트">
                      <input
                        type="number"
                        value={form.sshPort ?? 22}
                        onChange={e => setForm(f => ({ ...f, sshPort: Number(e.target.value) }))}
                        className={inputCls}
                      />
                    </Field>
                  </div>

                  <Field label="SSH 사용자명 *">
                    <input
                      required={!!form.sshEnabled}
                      value={form.sshUser ?? ''}
                      onChange={e => setForm(f => ({ ...f, sshUser: e.target.value }))}
                      placeholder="ec2-user"
                      className={inputCls}
                    />
                  </Field>

                  <Field label={editId !== null ? 'PEM 개인키 (변경 시만 입력)' : 'PEM 개인키 *'}>
                    <textarea
                      required={editId === null && !!form.sshEnabled}
                      value={form.sshPrivateKey ?? ''}
                      onChange={e => setForm(f => ({ ...f, sshPrivateKey: e.target.value }))}
                      placeholder={'-----BEGIN RSA PRIVATE KEY-----\n...\n-----END RSA PRIVATE KEY-----'}
                      rows={6}
                      className={inputCls + ' font-mono text-xs resize-y'}
                    />
                    <p className="mt-1 text-xs text-gray-400">PEM 파일 내용을 그대로 붙여넣으세요. 서버에서 암호화 저장됩니다.</p>
                  </Field>

                  <Field label="Passphrase (없으면 비워두세요)">
                    <div className="relative">
                      <input
                        type={showPassphrase ? 'text' : 'password'}
                        value={form.sshPassphrase ?? ''}
                        onChange={e => setForm(f => ({ ...f, sshPassphrase: e.target.value }))}
                        placeholder="PEM 키에 passphrase가 없으면 비워두세요"
                        className={inputCls + ' pr-9'}
                      />
                      <button
                        type="button"
                        onClick={() => setShowPassphrase(v => !v)}
                        className="absolute right-2 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
                      >
                        {showPassphrase ? <EyeOff size={14} /> : <Eye size={14} />}
                      </button>
                    </div>
                  </Field>
                </div>
              )}
            </div>

            {formError && (
              <p className="text-sm text-red-500">오류: {formError}</p>
            )}

            <div className="flex gap-2 pt-1">
              <button
                type="submit"
                disabled={isSaving}
                className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-1.5 rounded-lg text-sm font-medium"
              >
                {isSaving ? '저장 중…' : editId !== null ? '저장' : '등록'}
              </button>
              <button
                type="button"
                onClick={closeForm}
                className="text-gray-500 hover:text-gray-700 px-3 py-1.5 text-sm"
              >
                취소
              </button>
            </div>
          </form>
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
                    <tr
                      key={ds.id}
                      className="border-b border-gray-100 hover:bg-gray-50 transition-colors"
                    >
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-2">
                          <span className="font-medium text-gray-900">{ds.name}</span>
                          {ds.sshEnabled && (
                            <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-xs font-medium bg-amber-50 text-amber-700">
                              <Terminal size={10} /> SSH
                            </span>
                          )}
                        </div>
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
                            className="text-gray-400 hover:text-blue-600 hover:bg-blue-50 p-1.5 rounded transition-colors disabled:opacity-50"
                            title="연결 테스트"
                          >
                            <RefreshCw size={14} className={testingId === ds.id ? 'animate-spin' : ''} />
                          </button>
                          <button
                            onClick={() => openEdit(ds)}
                            className="text-gray-400 hover:text-gray-700 hover:bg-gray-100 p-1.5 rounded transition-colors"
                            title="수정"
                          >
                            <Pencil size={14} />
                          </button>
                          <button
                            onClick={() => handleDelete(ds)}
                            disabled={deleteMut.isPending}
                            className="text-gray-400 hover:text-red-600 hover:bg-red-50 p-1.5 rounded transition-colors disabled:opacity-50"
                            title="삭제"
                          >
                            <Trash2 size={14} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </div>
  )
}

// ── helpers ──────────────────────────────────────────────────────────────────

const inputCls =
  'w-full border border-gray-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500'

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="block text-xs font-medium text-gray-600 mb-1">{label}</label>
      {children}
    </div>
  )
}
