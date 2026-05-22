/*
 * Copyright (c) 2018-2021 Taner Sener
 *
 * This file is part of FFmpegKit.
 *
 * FFmpegKit is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * FFmpegKit is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with FFmpegKit.  If not, see <http://www.gnu.org/licenses/>.
 */

#ifndef FFMPEG_KIT_EXCEPTION_H
#define FFMPEG_KIT_EXCEPTION_H

#include <stdio.h>
#include <setjmp.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Holds information to implement exception handling. */
extern __thread jmp_buf ex_buf__;

/** Stores the exit code passed to exit_program() so the longjmp recipient
 *  (ffmpeg_execute / ffprobe_execute) can return it. */
extern __thread int longjmp_value;

void exit_program(int ret);

#ifdef __cplusplus
}
#endif

#endif // FFMPEG_KIT_EXCEPTION_H
