import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, Trash2, Wifi, WifiOff, ChevronDown, ChevronUp, Eye, EyeOff, Terminal } from 'lucide-react'
import {
  getDataSources,
  createDataSource,
  updateDataSource,
  deleteDataSource,
  testConnection,
  type DataSource,
  type DataSourceRequest,
} from '../../api/admin/dataSources'

const EMPTY_FORM: DataSourceRequest = {
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
  const [form, setForm] = useState<DataSourceRequest>(EMPTY_FORM)
  const [showPw, setShowPw] = useState(false)
  const [showPassphrase, setShowPassphrase] = useState(false)
  const [testResults, setTestResults] = useState<Record<number, { connected: boolean; message: string } | null>>({})
  const [expandedId, setExpandedId] = useState<number | null>(null)

  const { data: sources = [], isLoading, error } = useQuery({
    queryKey: ['admin-datasources'],
    queryFn: getDataSources,
  })

  const createMut = useMutation({
    mutationFn: createDataSource,
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['admin-datasources'] }); closeForm() },
  })

  const updateMut = useMutation({
    mutationFn: ({ id, body }: { id: number; body: Partial<DataSourceRequest> }) =>
      updateDataSource(id, body),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['admin-datasources'] }); closeForm() },
  })

  const deleteMut = useMutation({
    mutationFn: deleteDataSource,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['admin-datasources'] }),
  })

  const testMut = useMutation({
    mutationFn: testConnection,
    onSuccess: (res) => setTestResults(prev => ({ ...prev, [res.datasourceId]: res })),
  })

  const closeForm = () => {
    setShowForm(false)
    setEditId(null)
    setForm(EMPTY_FORM)
    setShowPw(false)
    setShowPassphrase(false)
  }

  const openCreate = () => {
    setEditId(null)
    setForm(EMPTY_FORM)
    setShowPw(false)
    setShowPassphrase(false)
    setShowForm(true)
  }

  const openEdit = (ds: DataSource) => {
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
    setShowPw(false)
    setShowPassphrase(false)
    setShowForm(true)
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const body: Partial<DataSourceRequest> = { ...form }
    if (!body.password) delete body.password
    if (!body.sshEnabled) {
      // SSH 비활성화 시 SSH 관련 필드 전송 안 함
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

  const isPending = createMut.isPending || updateMut.isPending

  return (
    <div className="p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">데이터소스 관리</h1>
          <p className="text-sm text-gray-500 mt-0.5">MySQL / MariaDB 소스 DB를 등록하고 관리합니다.</p>
        </div>
        <button
          onClick={openCreate}
          className="flex items-center gap-2 bg-blue-600 hover:bg-blue-700 text-white px-3 py-2 rounded-lg text-sm font-medium"
        >
          <Plus size={15} /> 데이터소스 추가
        </button>
      </div>

      {/* 등록/수정 폼 */}
      {showForm && (
        <form
          onSubmit={handleSubmit}
          className="mb-6 bg-white border border-gray-200 rounded-xl p-5 space-y-4"
        >
          <h2 className="text-sm font-semibold text-gray-800">
            {editId !== null ? '데이터소스 수정' : '새 데이터소스 등록'}
          </h2>

          <div className="grid grid-cols-2 gap-4">
            <Field label="이름 *">
              <input
                required
                value={form.name}
                onChange={e => setForm(p => ({ ...p, name: e.target.value }))}
                placeholder="CRM DB"
                className={inputCls}
              />
            </Field>
            <Field label="DB 종류">
              <select
                value={form.dbType}
                onChange={e => setForm(p => ({ ...p, dbType: e.target.value as 'mysql' | 'mariadb' }))}
                className={inputCls}
              >
                <option value="mysql">MySQL</option>
                <option value="mariadb">MariaDB</option>
              </select>
            </Field>
          </div>

          <Field label="설명 (LLM 라우팅 힌트)">
            <input
              value={form.description}
              onChange={e => setForm(p => ({ ...p, description: e.target.value }))}
              placeholder="이 DB에 어떤 데이터가 있는지 설명합니다. LLM이 DB 선택에 활용합니다."
              className={inputCls}
            />
          </Field>

          {editId === null && (
            <label className="flex items-start gap-2 cursor-pointer select-none bg-blue-50 border border-blue-200 rounded-lg px-3 py-2.5">
              <input
                type="checkbox"
                checked={!!form.autoDescribe}
                onChange={e => setForm(p => ({ ...p, autoDescribe: e.target.checked }))}
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
            <Field label="DB 호스트 *">
              <input
                required
                value={form.host}
                onChange={e => setForm(p => ({ ...p, host: e.target.value }))}
                placeholder="db.example.com 또는 RDS 엔드포인트"
                className={inputCls}
              />
            </Field>
            <Field label="포트">
              <input
                type="number"
                value={form.port}
                onChange={e => setForm(p => ({ ...p, port: Number(e.target.value) }))}
                className={inputCls}
              />
            </Field>
            <Field label="데이터베이스명 *">
              <input
                required
                value={form.dbName}
                onChange={e => setForm(p => ({ ...p, dbName: e.target.value }))}
                placeholder="crm_db"
                className={inputCls}
              />
            </Field>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <Field label="사용자명 *">
              <input
                required
                value={form.username}
                onChange={e => setForm(p => ({ ...p, username: e.target.value }))}
                placeholder="raguser"
                className={inputCls}
              />
            </Field>
            <Field label={editId !== null ? '비밀번호 (변경 시만 입력)' : '비밀번호 *'}>
              <div className="relative">
                <input
                  required={editId === null}
                  type={showPw ? 'text' : 'password'}
                  value={form.password}
                  onChange={e => setForm(p => ({ ...p, password: e.target.value }))}
                  placeholder={editId !== null ? '변경하지 않으면 비워두세요' : ''}
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
          </div>

          {/* SSH 터널 섹션 */}
          <div className="border border-gray-200 rounded-lg p-4 space-y-3">
            <label className="flex items-center gap-2 cursor-pointer select-none">
              <input
                type="checkbox"
                checked={!!form.sshEnabled}
                onChange={e => setForm(p => ({ ...p, sshEnabled: e.target.checked }))}
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
                        onChange={e => setForm(p => ({ ...p, sshHost: e.target.value }))}
                        placeholder="3.36.255.123"
                        className={inputCls}
                      />
                    </Field>
                  </div>
                  <Field label="SSH 포트">
                    <input
                      type="number"
                      value={form.sshPort ?? 22}
                      onChange={e => setForm(p => ({ ...p, sshPort: Number(e.target.value) }))}
                      className={inputCls}
                    />
                  </Field>
                </div>

                <Field label="SSH 사용자명 *">
                  <input
                    required={!!form.sshEnabled}
                    value={form.sshUser ?? ''}
                    onChange={e => setForm(p => ({ ...p, sshUser: e.target.value }))}
                    placeholder="ec2-user"
                    className={inputCls}
                  />
                </Field>

                <Field label={editId !== null ? 'PEM 개인키 (변경 시만 입력)' : 'PEM 개인키 *'}>
                  <textarea
                    required={editId === null && !!form.sshEnabled}
                    value={form.sshPrivateKey ?? ''}
                    onChange={e => setForm(p => ({ ...p, sshPrivateKey: e.target.value }))}
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
                      onChange={e => setForm(p => ({ ...p, sshPassphrase: e.target.value }))}
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

          {(createMut.error || updateMut.error) && (
            <p className="text-sm text-red-500">
              오류: {String(createMut.error ?? updateMut.error)}
            </p>
          )}

          <div className="flex gap-2 pt-1">
            <button
              type="submit"
              disabled={isPending}
              className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-1.5 rounded-lg text-sm font-medium"
            >
              {isPending ? '저장 중…' : editId !== null ? '저장' : '등록'}
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
      )}

      {isLoading && <p className="text-sm text-gray-400">불러오는 중…</p>}
      {error && <p className="text-sm text-red-500">오류: {String(error)}</p>}

      {/* 목록 */}
      <div className="space-y-3">
        {sources.map(ds => {
          const testResult = testResults[ds.id]
          const isExpanded = expandedId === ds.id

          return (
            <div
              key={ds.id}
              className="bg-white border border-gray-200 rounded-xl overflow-hidden"
            >
              <div className="flex items-center gap-4 px-5 py-4">
                <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-indigo-50 text-indigo-700 uppercase tracking-wide shrink-0">
                  {ds.dbType}
                </span>

                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-gray-900 text-sm">{ds.name}</span>
                    {ds.sshEnabled && (
                      <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-xs font-medium bg-amber-50 text-amber-700">
                        <Terminal size={10} /> SSH
                      </span>
                    )}
                  </div>
                  {ds.description && (
                    <div className="text-xs text-gray-400 truncate mt-0.5">{ds.description}</div>
                  )}
                </div>

                <div className="text-xs text-gray-500 font-mono shrink-0">
                  {ds.host}:{ds.port}/{ds.dbName}
                </div>

                <span
                  className={`inline-flex items-center px-2 py-0.5 rounded text-xs font-medium shrink-0 ${
                    ds.isActive ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-500'
                  }`}
                >
                  {ds.isActive ? '활성' : '비활성'}
                </span>

                {testResult && (
                  <span
                    className={`text-xs shrink-0 flex items-center gap-1 ${
                      testResult.connected ? 'text-green-600' : 'text-red-500'
                    }`}
                  >
                    {testResult.connected ? <Wifi size={12} /> : <WifiOff size={12} />}
                    {testResult.connected ? '연결 OK' : '연결 실패'}
                  </span>
                )}

                <div className="flex items-center gap-2 shrink-0">
                  <button
                    onClick={() => testMut.mutate(ds.id)}
                    disabled={testMut.isPending}
                    className="text-gray-400 hover:text-blue-600 disabled:opacity-40 text-xs border border-gray-200 hover:border-blue-300 rounded px-2 py-1"
                  >
                    테스트
                  </button>
                  <button
                    onClick={() => openEdit(ds)}
                    className="text-gray-400 hover:text-gray-700 text-xs border border-gray-200 hover:border-gray-300 rounded px-2 py-1"
                  >
                    수정
                  </button>
                  <button
                    onClick={() => {
                      if (window.confirm(`'${ds.name}' 데이터소스를 삭제하시겠습니까?`)) {
                        deleteMut.mutate(ds.id)
                      }
                    }}
                    className="text-red-400 hover:text-red-600"
                  >
                    <Trash2 size={14} />
                  </button>
                  <button
                    onClick={() => setExpandedId(isExpanded ? null : ds.id)}
                    className="text-gray-400 hover:text-gray-600"
                  >
                    {isExpanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
                  </button>
                </div>
              </div>

              {isExpanded && (
                <div className="border-t border-gray-100 px-5 py-3 bg-gray-50 text-xs text-gray-500 grid grid-cols-3 gap-2">
                  <div><span className="font-medium">사용자:</span> {ds.username}</div>
                  <div><span className="font-medium">등록일:</span> {new Date(ds.createdAt).toLocaleDateString('ko-KR')}</div>
                  <div><span className="font-medium">수정일:</span> {new Date(ds.updatedAt).toLocaleDateString('ko-KR')}</div>
                  {ds.sshEnabled && ds.sshHost && (
                    <div className="col-span-3 flex items-center gap-1.5 text-amber-700 mt-1">
                      <Terminal size={11} />
                      <span className="font-medium">SSH 터널:</span>
                      <span className="font-mono">{ds.sshUser}@{ds.sshHost}:{ds.sshPort}</span>
                      <span className="text-gray-400">→ {ds.host}:{ds.port}</span>
                    </div>
                  )}
                </div>
              )}
            </div>
          )
        })}

        {sources.length === 0 && !isLoading && (
          <div className="bg-white border border-gray-200 rounded-xl px-5 py-10 text-center text-sm text-gray-400">
            등록된 데이터소스가 없습니다.
          </div>
        )}
      </div>
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
