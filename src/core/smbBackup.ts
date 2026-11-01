/**
 * SMB 备份与恢复（洛雪音乐移动版）
 *
 * 备份内容：
 *  1. 全部歌单（试听列表、我的收藏、自建歌单），格式与官方「导出列表」的 playList_v2 一致
 *  2. 应用设置（@setting_v1）
 *  3. 自定义源脚本（音源）及其元数据，恢复后可直接使用，不必再手动导入
 *
 * 说明：本版本（1.9.2）已移除下载功能，因此不再备份音频文件。
 */
import { updateSetting } from '@/core/common'
import { getListMusics, overwriteListFull } from '@/core/list'
import { setUserApiList } from '@/core/userApi'
import { addUserApi, getUserApiList, getUserApiScript } from '@/utils/data'
import SmbClient, { type ISmbConfig, joinPath, smbSupported } from '@/utils/nativeModules/smb'
import { filterMusicList, fixNewMusicInfoQuality } from '@/utils'
import { log } from '@/utils/log'
import listState from '@/store/list/state'
import settingState from '@/store/setting/state'
import { PermissionsAndroid, Platform } from 'react-native'

export const BACKUP_FILE_NAME = 'lxmusic_backup.json'
export const BACKUP_VERSION = 1
export const DEFAULT_REMOTE_PATH = 'LXMusic'

export type SmbConfig = ISmbConfig

export interface ISmbProgress {
  phase: 'check' | 'list' | 'setting' | 'userApi' | 'done'
  current: number
  total: number
  detail?: string
}

export interface ISmbBackupResult {
  listCount: number
  musicCount: number
  userApiCount: number
  remotePath: string
}

export interface ISmbResumeResult {
  listCount: number
  musicCount: number
  userApiCount: number
}

export interface ISmbBackupPayload {
  lxBackup: number
  backupAt: number
  appVersion?: string
  /** playList_v2 结构，与官方导出列表兼容 */
  listData: {
    type: 'playList_v2'
    data: any[]
  }
  setting?: Record<string, any>
  userApi?: Array<{ info: LX.UserApi.UserApiInfo, script: string }>
}

/** Android 13+ 需要通知权限，否则看不到后台备份进度 */
async function ensureNotificationPermission() {
  try {
    if (Platform.OS !== 'android' || Number(Platform.Version) < 33) return
    const permission = PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
    if (!permission) return
    if (!await PermissionsAndroid.check(permission)) {
      await PermissionsAndroid.request(permission)
    }
  } catch {
    // 权限申请失败不影响备份本身
  }
}

/** 全局运行状态，切换页面后回来也能看到 */
let taskRunning = false

export const isRunning = () => taskRunning

function assertReady() {
  if (!smbSupported) throw new Error('当前设备不支持 SMB 备份（仅 Android 可用）')
}

/** 从设置中读取 SMB 配置 */
export const getSmbConfig = (): SmbConfig | null => {
  const setting = settingState.setting as Record<string, any>
  const host = setting['smb.host']
  const share = setting['smb.share']
  if (!host || !share) return null
  return {
    host,
    share,
    port: Number(setting['smb.port']) || 445,
    username: setting['smb.username'] ?? '',
    password: setting['smb.password'] ?? '',
    domain: setting['smb.domain'] ?? '',
  }
}

export const getRemoteRoot = () => {
  const configured = String((settingState.setting as Record<string, any>)['smb.remotePath'] ?? '').trim()
  return configured || DEFAULT_REMOTE_PATH
}

function requireConfig() {
  assertReady()
  const config = getSmbConfig()
  if (!config) throw new Error('请先在「SMB 备份」中完成服务器配置')
  return config
}

/** 收集全部歌单（与官方导出一致） */
async function getAllLists() {
  const lists: any[] = []
  lists.push({ ...listState.defaultList, list: await getListMusics(listState.defaultList.id) })
  lists.push({ ...listState.loveList, list: await getListMusics(listState.loveList.id) })
  for (const list of listState.userList) {
    lists.push({ ...list, list: await getListMusics(list.id) })
  }
  return lists
}

/** 恢复歌单，逻辑与官方导入 playList_v2 保持一致 */
async function importListData(lists: any[]) {
  const allLists = await getAllLists()
  for (const list of lists) {
    try {
      const targetList = allLists.find(l => l.id === list.id)
      const musics = filterMusicList(list.list ?? []).map((m: any) => fixNewMusicInfoQuality(m))
      if (targetList) {
        targetList.list = musics
      } else {
        allLists.push({
          name: list.name,
          id: list.id,
          list: musics,
          source: list.source,
          sourceListId: list.sourceListId,
          locationUpdateTime: list.locationUpdateTime ?? null,
        })
      }
    } catch (err) {
      log.error(err as Error)
    }
  }
  const defaultList = allLists.shift()!.list
  const loveList = allLists.shift()!.list
  await overwriteListFull({ defaultList, loveList, userList: allLists })
}

/** 把底层异常翻译成人能看懂的提示 */
export function friendlySmbError(e: any): string {
  const msg = String(e?.message ?? e ?? '')
  if (/getEncoded|signing|session key|Ntlm|null object reference/i.test(msg)) {
    return '认证异常：请检查用户名/密码是否正确（账号无效时部分 NAS 会降级为访客身份，导致连接中断）'
  }
  if (/STATUS_LOGON_FAILURE|STATUS_ACCESS_DENIED|Access denied/i.test(msg)) {
    return '用户名或密码错误，或该账号没有访问此共享的权限'
  }
  if (/STATUS_BAD_NETWORK_NAME|不是可用的磁盘共享|共享名/i.test(msg)) {
    return '共享名不存在：这里只填共享名（如 music），不要填完整路径'
  }
  if (/refused|timed out|timeout|No route|unreachable|ETIMEDOUT|EHOSTUNREACH/i.test(msg)) {
    return '无法连接服务器：请检查地址与端口，并确保手机和 NAS 在同一网络'
  }
  if (/UnknownHost|unknown host|EAI_AGAIN/i.test(msg)) {
    return '无法解析服务器地址，请检查地址是否填写正确'
  }
  return msg || '未知错误'
}

/** 测试连接，并检查根目录是否可写 */
export async function testConnection() {
  const config = requireConfig()
  await SmbClient.testConnection(config)
  const root = getRemoteRoot()
  let writable = true
  try {
    await SmbClient.mkdir(config, root)
  } catch {
    writable = false
  }
  return { ok: true, writable, root }
}

/**
 * 备份到 SMB
 */
export async function backupToSmb(
  onProgress?: (progress: ISmbProgress) => void,
): Promise<ISmbBackupResult> {
  if (taskRunning) throw new Error('已有备份/恢复任务正在进行中')
  taskRunning = true
  const config = requireConfig()
  const root = getRemoteRoot()
  const report = (progress: ISmbProgress) => {
    onProgress?.(progress)
    void SmbClient.updateBackgroundService('SMB 备份', progress.detail ?? '', progress.current, progress.total)
  }

  await ensureNotificationPermission()
  await SmbClient.startBackgroundService('SMB 备份', '正在连接 SMB')
  try {
    report({ phase: 'check', current: 0, total: 0, detail: '正在连接 SMB' })
    try {
      await SmbClient.mkdir(config, root)
    } catch (e: any) {
      throw new Error(`[连接 SMB] ${friendlySmbError(e)}`)
    }

    report({ phase: 'list', current: 0, total: 1, detail: '正在读取歌单' })
    let listData: ISmbBackupPayload['listData']
    let musicCount = 0
    let listCount = 0
    try {
      const lists = await getAllLists()
      listData = { type: 'playList_v2', data: lists }
      listCount = lists.length
      for (const l of lists) musicCount += l.list?.length ?? 0
    } catch (e: any) {
      throw new Error(`[读取歌单] ${e?.message ?? e}`)
    }

    report({ phase: 'setting', current: 0, total: 1, detail: '正在读取设置' })
    const setting = JSON.parse(JSON.stringify(settingState.setting)) as Record<string, any>

    report({ phase: 'userApi', current: 0, total: 1, detail: '正在读取自定义源' })
    const userApi: ISmbBackupPayload['userApi'] = []
    try {
      const infos = await getUserApiList()
      for (const info of infos) {
        try {
          userApi.push({ info, script: await getUserApiScript(info.id) })
        } catch (e) {
          log.error(e as Error)
        }
      }
    } catch (e) {
      log.error(e as Error)
    }

    const payload: ISmbBackupPayload = {
      lxBackup: BACKUP_VERSION,
      backupAt: Date.now(),
      listData,
      setting,
      userApi,
    }

    report({ phase: 'done', current: 0, total: 1, detail: '正在写入备份文件' })
    try {
      await SmbClient.writeText(config, joinPath(root, BACKUP_FILE_NAME), JSON.stringify(payload))
    } catch (e: any) {
      throw new Error(`[写入备份文件] ${friendlySmbError(e)}`)
    }

    updateSetting({ 'smb.lastBackupAt': payload.backupAt })

    return { listCount, musicCount, userApiCount: userApi.length, remotePath: root }
  } finally {
    taskRunning = false
    void SmbClient.stopBackgroundService()
  }
}

/**
 * 从 SMB 恢复
 * @param options.withSetting 是否一并恢复设置
 * @param options.withUserApi 是否一并恢复自定义源
 */
export async function resumeFromSmb(
  options: { withSetting?: boolean, withUserApi?: boolean } = {},
  onProgress?: (progress: ISmbProgress) => void,
): Promise<ISmbResumeResult> {
  if (taskRunning) throw new Error('已有备份/恢复任务正在进行中')
  taskRunning = true
  const config = requireConfig()
  const root = getRemoteRoot()
  const report = (progress: ISmbProgress) => {
    onProgress?.(progress)
    void SmbClient.updateBackgroundService('SMB 恢复', progress.detail ?? '', progress.current, progress.total)
  }

  await ensureNotificationPermission()
  await SmbClient.startBackgroundService('SMB 恢复', '正在读取备份')
  try {
    report({ phase: 'check', current: 0, total: 0, detail: '正在读取备份' })
    let payload: ISmbBackupPayload
    try {
      const raw = await SmbClient.readText(config, joinPath(root, BACKUP_FILE_NAME))
      payload = JSON.parse(raw) as ISmbBackupPayload
    } catch (e: any) {
      throw new Error(`[读取备份] ${friendlySmbError(e)}`)
    }
    if (!payload?.listData?.data) {
      throw new Error('备份文件格式不正确，请确认远端存在本应用生成的备份')
    }

    report({ phase: 'list', current: 0, total: 1, detail: '正在恢复歌单' })
    let musicCount = 0
    try {
      const lists = payload.listData.data
      for (const l of lists) musicCount += l.list?.length ?? 0
      await importListData(lists)
    } catch (e: any) {
      throw new Error(`[恢复歌单] ${e?.message ?? e}`)
    }

    if (options.withSetting && payload.setting) {
      report({ phase: 'setting', current: 0, total: 1, detail: '正在恢复设置' })
      updateSetting(payload.setting as Partial<LX.AppSetting>)
    }

    let userApiCount = 0
    if (options.withUserApi && payload.userApi?.length) {
      for (let i = 0; i < payload.userApi.length; i++) {
        report({ phase: 'userApi', current: i + 1, total: payload.userApi.length, detail: '正在恢复自定义源' })
        const item = payload.userApi[i]
        try {
          await addUserApi(item.script)
          userApiCount += 1
        } catch (e) {
          log.error(e as Error)
        }
      }
      try {
        setUserApiList(await getUserApiList())
      } catch (e) {
        log.error(e as Error)
      }
    }

    return { listCount: payload.listData.data.length, musicCount, userApiCount }
  } finally {
    taskRunning = false
    void SmbClient.stopBackgroundService()
  }
}

/** 查询远端备份概览 */
export async function getRemoteBackupInfo() {
  const config = requireConfig()
  const root = getRemoteRoot()
  const remoteFile = joinPath(root, BACKUP_FILE_NAME)
  const info = await SmbClient.stat(config, remoteFile)
  if (!info?.exists) return null
  try {
    const payload = JSON.parse(await SmbClient.readText(config, remoteFile)) as ISmbBackupPayload
    return {
      backupAt: payload?.backupAt,
      listCount: payload?.listData?.data?.length ?? 0,
      userApiCount: payload?.userApi?.length ?? 0,
      size: info.size,
    }
  } catch {
    return { backupAt: undefined, listCount: 0, userApiCount: 0, size: info.size }
  }
}

/** 读取本地记录的最近一次备份时间 */
export const getLastBackupAt = () => Number((settingState.setting as Record<string, any>)['smb.lastBackupAt']) || 0
