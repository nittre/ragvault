import apiClient from '../client'

export interface SqlTable {
  id: number
  sourceTable: string
  displayName: string
  description: string
  allowedColumns: string[] | null
  excludedColumns: string[] | null
  dataSensitivity: string
  isActive: boolean
  datasourceId: number
  llmStatus: string
  createdAt: string
  updatedAt: string
}

const base = (dsId: number) => `/api/v1/admin/datasources/${dsId}/sql-tables`

export const getSqlTables = (dsId: number) =>
  apiClient.get<SqlTable[]>(base(dsId)).then(r => r.data)

export const createSqlTable = (dsId: number, body: Partial<SqlTable>) =>
  apiClient.post<SqlTable>(base(dsId), body).then(r => r.data)

export const deleteSqlTable = (dsId: number, id: number) =>
  apiClient.delete(`${base(dsId)}/${id}`).then(r => r.data)

export const refreshSchemaCache = (dsId: number) =>
  apiClient.post<void>(`${base(dsId)}/refresh-schema-cache`).then(r => r.data)

export const updateSqlTable = (
  dsId: number,
  id: number,
  body: { dataSensitivity?: string; isActive?: boolean; displayName?: string; description?: string },
) => apiClient.patch<SqlTable>(`${base(dsId)}/${id}`, body).then(r => r.data)

export const bulkDeleteSqlTables = (dsId: number, ids: number[]) =>
  apiClient
    .delete<{ succeeded: number[]; failed: number[] }>(`${base(dsId)}/bulk`, { data: { ids } })
    .then(r => r.data)

export const bulkImportSqlTables = (dsId: number, tableNames: string[]) =>
  apiClient
    .post<{ imported: string[]; skipped: string[] }>(`${base(dsId)}/bulk-import`, { tableNames })
    .then(r => r.data)

export interface ColumnDescription {
  columnName: string
  dataType: string
  description: string
  source: string
}

export const getColumnDescriptions = (dsId: number, id: number) =>
  apiClient.get<ColumnDescription[]>(`${base(dsId)}/${id}/columns`).then(r => r.data)

export const updateColumnDescriptions = (
  dsId: number,
  id: number,
  columns: { columnName: string; description: string }[],
) => apiClient.put<void>(`${base(dsId)}/${id}/columns`, { columns }).then(r => r.data)
