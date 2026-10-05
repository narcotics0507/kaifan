/** Default theme settings */
export const themeSettings: App.Theme.ThemeSetting = {
  themeScheme: 'light',
  grayscale: false,
  colourWeakness: false,
  recommendColor: false,
  themeColor: '#a94d24',
  themeRadius: 12,
  otherColor: {
    info: '#14a3ff',
    success: '#21a67a',
    warning: '#f0a11a',
    error: '#e0564a'
  },
  isInfoFollowPrimary: true,
  layout: {
    mode: 'vertical',
    scrollMode: 'content'
  },
  page: {
    animate: true,
    animateMode: 'fade-slide'
  },
  header: {
    height: 56,
    breadcrumb: {
      visible: true,
      showIcon: true
    },
    multilingual: {
      visible: false
    },
    globalSearch: {
      visible: true
    }
  },
  tab: {
    visible: true,
    cache: true,
    height: 44,
    mode: 'chrome',
    closeTabByMiddleClick: false
  },
  fixedHeaderAndTab: true,
  sider: {
    inverted: false,
    width: 220,
    collapsedWidth: 64,
    mixWidth: 90,
    mixCollapsedWidth: 64,
    mixChildMenuWidth: 200,
    autoSelectFirstMenu: false
  },
  footer: {
    visible: true,
    fixed: false,
    height: 48,
    right: true
  },
  watermark: {
    visible: false,
    text: 'Henfon',
    enableUserName: false,
    enableTime: false,
    timeFormat: 'YYYY-MM-DD HH:mm'
  },
  tokens: {
    light: {
      colors: {
        container: 'rgb(255, 250, 243)',
        layout: 'rgb(244, 239, 231)',
        inverted: 'rgb(62, 48, 38)',
        'base-text': 'rgb(53, 42, 35)'
      },
      boxShadow: {
        header: '0 12px 30px rgb(62, 48, 38, 0.04)',
        sider: '12px 0 28px 0 rgb(62, 48, 38, 0.03)',
        tab: '0 10px 24px rgb(62, 48, 38, 0.03)'
      }
    },
    dark: {
      colors: {
        container: 'rgb(28, 28, 28)',
        layout: 'rgb(18, 18, 18)',
        'base-text': 'rgb(224, 224, 224)'
      }
    }
  }
};

/**
 * Override theme settings
 *
 * If publish new version, use `overrideThemeSettings` to override certain theme settings
 */
export const overrideThemeSettings: Partial<App.Theme.ThemeSetting> = {
  themeScheme: 'light',
  themeColor: '#a94d24',
  themeRadius: 12,
  otherColor: {
    info: '#14a3ff',
    success: '#21a67a',
    warning: '#f0a11a',
    error: '#e0564a'
  },
  isInfoFollowPrimary: true,
  tokens: {
    light: {
      colors: {
        container: 'rgb(255, 250, 243)',
        layout: 'rgb(244, 239, 231)',
        inverted: 'rgb(62, 48, 38)',
        'base-text': 'rgb(53, 42, 35)'
      },
      boxShadow: {
        header: '0 12px 30px rgb(62, 48, 38, 0.04)',
        sider: '12px 0 28px 0 rgb(62, 48, 38, 0.03)',
        tab: '0 10px 24px rgb(62, 48, 38, 0.03)'
      }
    }
  }
};
