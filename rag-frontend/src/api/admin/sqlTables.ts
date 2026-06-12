import apiClient from '../client'

export interface SqlTable {
  id: string
  tableName: string
  columnsJson: string
  active: boolean
}

export const getSqlTables = () =>
  apiClient.get<SqlTable[]>('/api/v1/admin/sql-tables').then(r => r.data)

export const createSqlTable = (body: { tableName: string; columnsJson?: string }) =>
  apiClient.post<SqlTable>('/api/v1/admin/sql-tables', body).then(r => r.data)

export const deleteSqlTable = (id: string) =>
  apiClient.delete(`/api/v1/admin/sql-tables/${id}`).then(r => r.data)

export const refreshSchemaCache = () =>
  apiClient.post<void>('/api/v1/admin/sql-tables/refresh-schema-cache').then(r => r.data)
