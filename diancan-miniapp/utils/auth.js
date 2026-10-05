const { env } = require('../config/env');
const { requestRaw } = require('./request');
const { KEYS, get, set } = require('./storage');

/**
 * 调用 wx.login 获取临时凭证 code
 */
function wxLogin() {
  return new Promise((resolve, reject) => {
    wx.login({
      success: (res) => {
        if (res.code) resolve(res.code);
        else reject(new Error('获取微信登录 code 失败'));
      },
      fail: reject
    });
  });
}

/**
 * 从本地存储恢复登录态
 */
function restoreSession() {
  return {
    token: get(KEYS.TOKEN),
    openid: get(KEYS.OPENID)
  };
}

/**
 * 检查是否有有效 token
 */
function isLoggedIn() {
  return !!get(KEYS.TOKEN);
}

/** 同一页面并发点击只交换一次临时凭证。 */
let pendingLogin = null;
function wechatLogin() {
  if (pendingLogin) return pendingLogin;
  pendingLogin = (async () => {
    const code = await wxLogin();
    const res = await requestRaw({
      url: env.loginPath, method: 'POST', data: { code }, withPrefix: false
    });
    const data = res.data || {};
    if (!data.token) throw new Error('微信登录未成功，请重试');
    set(KEYS.TOKEN, data.token);
    set(KEYS.USER_INFO, {
      userId: data.userId, nickname: data.nickname, avatar: data.avatar
    });
    return data.token;
  })().finally(() => { pendingLogin = null; });
  return pendingLogin;
}

module.exports = { wxLogin, restoreSession, isLoggedIn, wechatLogin };
