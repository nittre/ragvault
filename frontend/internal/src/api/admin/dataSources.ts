import apiClient from '../client'

export interface DataSource {
  id: number
  name: string
  description: string | null
  dbType: 'mysql' | 'mariadb'
  host: string
  port: number
  dbName: string
  username: string
  isActive: boolean
  sshEnabled: boolean
  sshHost: string | null
  sshPort: number | null
  sshUser: string | null
  createdAt: string
  updatedAt: string
}

export interface DataSourceRequest {
  name: string
  description?: string
  dbType: 'mysql' | 'mariadb'
  host: string
  port: number
  dbName: string
  username: string
  password?: string
  // SSH 터널 설정
  sshEnabled?: boolean
  sshHost?: string
  sshPort?: number
  sshUser?: string
  sshPrivateKey?: string  // PEM 전문 — 서버에서 암호화 저장
  sshPassphrase?: string  // passphrase — 서버에서 암호화 저장
  autoDescribe?: boolean  // 등록 시 LLM으로 테이블·컬럼 설명 자동 생성
}

export const getDataSources = () =>
  apiClient.get<DataSource[]>('/api/v1/admin/datasources').then(r => r.data)

export const createDataSource = (body: DataSourceRequest) =>
  apiClient.post<DataSource>('/api/v1/admin/datasources', body).then(r => r.data)

export const updateDataSource = (id: number, body: Partial<DataSourceRequest>) =>
  apiClient.patch<DataSource>(`/api/v1/admin/datasources/${id}`, body).then(r => r.data)

export const deleteDataSource = (id: number) =>
  apiClient.delete(`/api/v1/admin/datasources/${id}`)

export const testConnection = (id: number) =>
  apiClient
    .post<{ datasourceId: number; connected: boolean; message: string }>(
      `/api/v1/admin/datasources/${id}/test`
    )
    .then(r => r.data)
