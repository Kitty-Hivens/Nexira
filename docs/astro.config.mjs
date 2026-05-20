import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

export default defineConfig({
  integrations: [
    starlight({
      title: 'Nexira',
      description: 'An unofficial launcher for SMARTYcraft',
      social: [
        { icon: 'github', label: 'GitHub', href: 'https://github.com/Kitty-Hivens/Nexira' },
      ],
      editLink: {
        baseUrl: 'https://github.com/Kitty-Hivens/Nexira/edit/stable/docs/',
      },
      customCss: ['./src/styles/custom.css'],
      defaultLocale: 'root',
      locales: {
        root: { label: 'English', lang: 'en' },
        ru: { label: 'Русский', lang: 'ru' },
      },
      sidebar: [
        {
          label: 'Getting Started',
          translations: { ru: 'Начало работы' },
          items: [
            { label: 'Installation', slug: 'installation', translations: { ru: 'Установка' } },
            { label: 'Troubleshooting', slug: 'troubleshooting', translations: { ru: 'Устранение неполадок' } },
          ],
        },
        {
          label: 'For Developers',
          translations: { ru: 'Для разработчиков' },
          items: [
            { label: 'Building from Source', slug: 'dev/building', translations: { ru: 'Сборка из исходников' } },
            { label: 'Architecture', slug: 'dev/architecture', translations: { ru: 'Архитектура' } },
          ],
        },
      ],
    }),
  ],
  site: 'https://kitty-hivens.github.io',
  base: '/Nexira',
});
