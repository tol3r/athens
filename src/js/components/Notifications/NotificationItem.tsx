import React from 'react';
import { BulletIcon, ArchiveIcon, ArrowLeftOnBoxIcon, CheckboxIcon, UnreadIcon } from "@/Icons/Icons";
import { mapActionsToButtons } from "@/utils/mapActionsToButtons";
import { useTheme, Flex, Box, ButtonGroup, HStack, MenuGroup, MenuItem, Text, VStack } from "@chakra-ui/react";
import { motion } from "framer-motion";
import { ContextMenuContext } from "@/App/ContextMenuContext";

const verbForType = (type: string): string => {
  if (type === "Created") {
    return "created";
  } else if (type === "Edited") {
    return "edited";
  } else if (type === "Deleted") {
    return "deleted";
  } else if (type === "Comments") {
    return "commented on";
  } else if (type === "Mentions") {
    return "mentioned you in";
  } else if (type === "Assignments") {
    return "assigned you to";
  } else if (type === "Completed") {
    return "completed";
  }
}

export const NotificationItem = (props) => {
  const { notification, ...otherProps } = props;
  const { id, isRead, body, object, notificationTime } = notification;
  const { onOpenItem, onMarkAsRead, onMarkAsUnread, onArchive, onUnarchive, ...boxProps } = otherProps;
  const { addToContextMenu, getIsMenuOpen } = React.useContext(ContextMenuContext);
  const ref = React.useRef(null);
  const isMenuOpen = getIsMenuOpen(ref);
  const theme = useTheme();
  const indicatorHeight = `calc(${theme.lineHeights.base} * ${theme.fontSizes.md})`;

  const getActionsForNotification = (notification) => {
    const actions = [];
    if (notification.isArchived) {
      actions.push({
        label: "Unarchive",
        fn: () => onUnarchive(notification.id),
        icon: <ArchiveIcon />
      });
    } else {
      actions.push({
        label: "Archive",
        fn: (e) => onArchive(e, notification.id),
        icon: <ArchiveIcon />
      });
    }
    return actions;
  }

  const ContextMenuItems = () => {
    return <MenuGroup>
      <MenuItem onClick={(e) => onOpenItem(e, object.parentUid, id)} icon={<ArrowLeftOnBoxIcon />}>Open {object.name ? "page" : "block"}</MenuItem>
      {isRead
        ? <MenuItem onClick={(e) => onMarkAsUnread(e, id)} icon={<UnreadIcon />}>Mark as unread</MenuItem>
        : <MenuItem onClick={(e) => onMarkAsRead(e, id)} icon={<CheckboxIcon />}>Mark as read</MenuItem>}
      <MenuItem onClick={(e) => onArchive(e, id)} icon={<ArchiveIcon />}>Archive</MenuItem>
    </MenuGroup>
  }

  return <Box
    key={id + notificationTime}
    layout
    initial={{
      height: 0,
      opacity: 0,
    }}
    animate={{
      height: "auto",
      opacity: 1,
    }}
    exit={{
      height: 0,
      opacity: 0,
    }}
    ref={ref}
    flexShrink={0}
    overflow="hidden"
    as={motion.div}
  ><VStack
    p={2}
    spacing={1}
    align="stretch"
    userSelect="none"
    transitionProperty="common"
    transitionDuration="fast"
    transitionTimingFunction="ease-in-out"
    boxShadow={isMenuOpen ? "focusInset" : "none"}
    borderRadius="md"
    bg={isRead ? "transparent" : "interaction.surface"}
    color={isRead ? "foreground.secondary" : "foreground.primary"}
    _hover={{
      cursor: "pointer",
      bg: "interaction.surface.hover"
    }}
    onClick={(e) => { if (e?.button === 0) onOpenItem(object.parentUid, id) }}
    onContextMenu={(e) => {
      addToContextMenu({ event: e, component: ContextMenuItems, ref });
    }}
    {...boxProps}
  >
      <HStack
        alignItems="flex-start"
        textAlign="left"
        spacing={1.5}
      >
        <Flex
          width={2}
          flexShrink={0}
          placeItems="center"
          placeContent="center"
          height={indicatorHeight}
        >
          {isRead ? (null) : (
            <BulletIcon
              fontSize="3xl"
              color="info"
            />
          )}
        </Flex>
        <VStack flexShrink={1} spacing={0} align="stretch">
          <Text fontWeight="bold" noOfLines={2} fontSize="sm">
            {notification.subject.username.slice(1)} {verbForType(notification.type)} "{object.name || object.string}"
          </Text>
          {body && <Text fontSize="sm">{body}</Text>}
        </VStack>
      </HStack>
      <HStack justifyContent="space-between" >
        <Text fontSize="sm"
          marginLeft="14px"
          color="gray">{notificationTime}</Text>
        <ButtonGroup
          variant="ghost"
          flex="0 0 auto"
          onClick={(e) => e.stopPropagation()}
          size="xs"
          alignSelf="flex-end"
        >
          {mapActionsToButtons(getActionsForNotification(notification), 1)}
        </ButtonGroup>
      </HStack>
    </VStack>
  </Box>
}

