import { memo, useCallback, useEffect, useState } from 'react'
import { ActivityIndicator, View } from 'react-native'

import SubTitle from '../../components/SubTitle'
import Button from '../../components/Button'
import InputItem from '../../components/InputItem'
import CheckBoxItem from '../../components/CheckBoxItem'
import Text from '@/components/common/Text'
import { useI18n } from '@/lang'
import { useSettingValue } from '@/store/setting/hook'
import { updateSetting } from '@/core/common'
import { confirmDialog, createStyle, toast } from '@/utils/tools'
import { dateFormat2 } from '@/utils'
import {
  backupToSmb,
  friendlySmbError,
  getLastBackupAt,
  getRemoteBackupInfo,
  isRunning,
  resumeFromSmb,
  testConnection,
  type ISmbProgress,
} from '@/core/smbBackup'

const formatSize = (size: number) => {
  if (!size) return '0 B'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

export default memo(() => {
  const t = useI18n()
  const host = useSettingValue('smb.host')
  const port = useSettingValue('smb.port')
  const share = useSettingValue('smb.share')
  const username = useSettingValue('smb.username')
  const password = useSettingValue('smb.password')
  const domain = useSettingValue('smb.domain')
  const remotePath = useSettingValue('smb.remotePath')
  const resumeSetting = useSettingValue('smb.resumeSetting')
  const resumeUserApi = useSettingValue('smb.resumeUserApi')

  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState<ISmbProgress | null>(null)
  const [remoteInfo, setRemoteInfo] = useState<{ backupAt?: number, listCount: number, userApiCount: number, size: number } | null | undefined>(undefined)

  const lastBackupAt = getLastBackupAt()

  const refreshRemoteInfo = useCallback(() => {
    void getRemoteBackupInfo().then(info => {
      setRemoteInfo(info)
    }).catch(() => {
      setRemoteInfo(null)
    })
  }, [])

  useEffect(() => {
    if (host && share) refreshRemoteInfo()
    else setRemoteInfo(null)
  }, [host, share, refreshRemoteInfo])

  const runTask = useCallback(async(task: () => Promise<string>) => {
    if (busy || isRunning()) return
    setBusy(true)
    try {
      toast(await task())
    } catch (e: any) {
      toast(e?.message?.startsWith('[') ? e.message : friendlySmbError(e))
    } finally {
      setBusy(false)
      setProgress(null)
      refreshRemoteInfo()
    }
  }, [busy, refreshRemoteInfo])

  const handleTest = () => {
    void runTask(async() => {
      const res = await testConnection()
      return res.writable ? t('setting_smb_test_success', { root: res.root }) : t('setting_smb_test_readonly', { root: res.root })
    })
  }

  const handleBackup = () => {
    void runTask(async() => {
      const res = await backupToSmb(setProgress)
      return t('setting_smb_backup_success', {
        list: res.listCount,
        music: res.musicCount,
        api: res.userApiCount,
      })
    })
  }

  const handleResume = () => {
    void confirmDialog({
      message: t('setting_smb_resume_confirm'),
      cancelButtonText: t('dialog_cancel'),
      confirmButtonText: t('confirm_button_text'),
      bgClose: false,
    }).then(confirm => {
      if (!confirm) return
      void runTask(async() => {
        const res = await resumeFromSmb({ withSetting: resumeSetting, withUserApi: resumeUserApi }, setProgress)
        return t('setting_smb_resume_success', {
          list: res.listCount,
          music: res.musicCount,
          api: res.userApiCount,
        })
      })
    })
  }

  const progressText = progress
    ? `${progress.detail ?? ''}${progress.total > 0 ? ` (${progress.current}/${progress.total})` : ''}`
    : ''

  let remoteDesc = t('setting_smb_remote_unknown')
  if (remoteInfo === null) remoteDesc = t('setting_smb_remote_none')
  else if (remoteInfo) {
    remoteDesc = t('setting_smb_remote_info', {
      time: remoteInfo.backupAt ? dateFormat2(remoteInfo.backupAt) : '-',
      list: remoteInfo.listCount,
      api: remoteInfo.userApiCount,
      size: formatSize(remoteInfo.size),
    })
  }

  return (
    <>
      <SubTitle title={t('setting_smb_address')}>
        <InputItem label={t('setting_smb_host')} value={host} placeholder="192.168.1.2" onChanged={v => updateSetting({ 'smb.host': v })} />
        <InputItem label={t('setting_smb_share')} value={share} placeholder="music" onChanged={v => updateSetting({ 'smb.share': v })} />
        <InputItem label={t('setting_smb_port')} value={String(port || 445)} keyboardType="numeric" onChanged={v => updateSetting({ 'smb.port': Number(v) || 445 })} />
      </SubTitle>

      <SubTitle title={t('setting_smb_auth')}>
        <InputItem label={t('setting_smb_username')} value={username} onChanged={v => updateSetting({ 'smb.username': v })} />
        <InputItem label={t('setting_smb_password')} value={password} secureTextEntry onChanged={v => updateSetting({ 'smb.password': v })} />
        <InputItem label={t('setting_smb_domain')} value={domain} onChanged={v => updateSetting({ 'smb.domain': v })} />
      </SubTitle>

      <SubTitle title={t('setting_smb_backup_dir')}>
        <InputItem label={t('setting_smb_remote_path')} value={remotePath} placeholder="LXMusic" onChanged={v => updateSetting({ 'smb.remotePath': v })} />
      </SubTitle>

      <SubTitle title={t('setting_smb_action')}>
        <View style={styles.remoteInfo}>
          <Text size={13}>{remoteDesc}</Text>
          {lastBackupAt ? <Text size={13}>{t('setting_smb_last_backup', { time: dateFormat2(lastBackupAt) })}</Text> : null}
          {progressText ? <Text size={13}>{progressText}</Text> : null}
        </View>
        <View style={styles.buttons}>
          <Button disabled={busy} onPress={handleTest}>{t('setting_smb_test')}</Button>
          <Button disabled={busy} onPress={handleBackup}>{t('setting_smb_backup')}</Button>
          <Button disabled={busy} onPress={handleResume}>{t('setting_smb_resume')}</Button>
          {busy ? <ActivityIndicator size="small" /> : null}
        </View>
        <View style={styles.options}>
          <CheckBoxItem check={resumeUserApi} label={t('setting_smb_resume_user_api')} onChange={v => updateSetting({ 'smb.resumeUserApi': v })} />
          <CheckBoxItem check={resumeSetting} label={t('setting_smb_resume_setting')} onChange={v => updateSetting({ 'smb.resumeSetting': v })} />
        </View>
      </SubTitle>
    </>
  )
})

const styles = createStyle({
  remoteInfo: {
    marginBottom: 8,
  },
  buttons: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
  },
  options: {
    marginTop: 10,
  },
})
