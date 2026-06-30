import apiClient from '../client'

export interface ColumnDetail {
  name: string
  dataType: string
  nullable: boolean
  comment: string
  primaryKey: boolean
}

export interface TableInfo {
  tableName: string
  tableComment: string
  columns: ColumnDetail[]
}

export const getSchema = (dsId: number) =>
  apiClient
    .get<TableInfo[]>('/api/v1/admin/schema/tables', { params: { datasourceId: dsId } })
    .then(r => r.data)
